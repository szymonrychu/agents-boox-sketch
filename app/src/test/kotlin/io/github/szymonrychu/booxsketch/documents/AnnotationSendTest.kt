package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.render.ExportResult
import io.github.szymonrychu.booxsketch.send.CANVAS
import io.github.szymonrychu.booxsketch.send.ConfigCache
import io.github.szymonrychu.booxsketch.send.ConnectorDouble
import io.github.szymonrychu.booxsketch.send.FakeBoard
import io.github.szymonrychu.booxsketch.send.OutboxRunner
import io.github.szymonrychu.booxsketch.send.SendOutcome
import io.github.szymonrychu.booxsketch.send.SendUseCase
import io.github.szymonrychu.booxsketch.send.metaOf
import io.github.szymonrychu.booxsketch.send.page
import io.github.szymonrychu.booxsketch.send.partsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AnnotationSendTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var double: ConnectorDouble
    private lateinit var client: ConnectorClient
    private lateinit var store: AnnotationStore
    private lateinit var useCase: SendUseCase
    private lateinit var background: File
    private var limits: ConfigDto = ConfigCache.DEFAULT
    private var ids = 0
    private var exporter: () -> ExportResult = { ExportResult.Exported(ByteArray(PNG_SIZE) { 1 }, 300, 400) }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        double = ConnectorDouble()
        server = MockWebServer().apply { dispatcher = double }
        server.start()
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        store = AnnotationStore(tmp.newFolder("files"))
        background =
            File(tmp.root, "bg.png").also { f ->
                f.outputStream().use { Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        val outboxRoot = tmp.newFolder("outbox")
        val repo =
            ConversationRepository(db, { client }, context.getSharedPreferences("annotation-send", Context.MODE_PRIVATE), outboxRoot, {})
        useCase =
            SendUseCase(
                db = db,
                conversations = repo,
                board = FakeBoard(Notebook(CANVAS, listOf(page(true, "a")), 0)),
                export = { _, _, _ -> ExportResult.Empty },
                limits = { limits },
                outboxRoot = outboxRoot,
                kick = {},
                worker = Dispatchers.Unconfined,
                newId = { "00000000-0000-4000-8000-0000000000${"%02d".format(++ids)}" },
                annotations = store,
                exportPage = { _, _, _, _ -> exporter() },
            )
        runBlocking { db.conversationDao().upsert(ConversationEntity("c1", "srv-1", "Doc", 0, 0)) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun input(
        index: Int,
        inked: Boolean = true,
    ) = AnnotatedInput(
        index,
        background,
        if (inked) page(true, "s$index") else Page(emptyList(), false),
        docPage = index,
        lines =
            (index * 10)..(index * 10 + 9),
    )

    private suspend fun send(
        pages: List<AnnotatedInput>,
        version: Int = 1,
        latest: Int = 1,
        caption: String? = null,
    ) = useCase.sendAnnotation("c1", version, latest, pages, caption)

    private fun runner() = OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {}, annotations = store)

    @Test
    fun only_inked_pages_uploaded() =
        runBlocking<Unit> {
            assertThat(send(listOf(input(0), input(1, inked = false), input(2)))).isInstanceOf(SendOutcome.Queued::class.java)
            runner().drain()
            val request = double.requests.single()
            assertThat(partsOf(request).filter { it.endsWith(".png") }).containsExactly("sketch.0.png", "sketch.1.png").inOrder()
            assertThat(
                metaOf(request)["pages"]!!.jsonArray.map { it.jsonObject["page"]!!.jsonPrimitive.int },
            ).containsExactly(0, 2).inOrder()
        }

    @Test
    fun stale_version_writes_nothing() =
        runBlocking<Unit> {
            assertThat(send(listOf(input(0)), version = 1, latest = 2)).isEqualTo(SendOutcome.StaleVersion(1, 2))
            assertThat(db.outboxDao().forConversation("c1")).isEmpty()
            assertThat(db.threadDao().list("c1")).isEmpty()
            assertThat(store.isSent("c1", 1)).isFalse()
        }

    @Test
    fun five_inked_pages_split_into_two_sends_in_order_with_max_pages_4() =
        runBlocking<Unit> {
            limits = ConfigCache.DEFAULT.copy(maxPages = 4)
            assertThat(send((0..4).map { input(it) })).isInstanceOf(SendOutcome.Queued::class.java)
            assertThat(db.outboxDao().forConversation("c1")).hasSize(2)
            runner().drain()
            val (first, second) = double.requests.map(::metaOf)
            assertThat(first["pages"]!!.jsonArray).hasSize(4)
            assertThat(second["pages"]!!.jsonArray.map { it.jsonObject["page"]!!.jsonPrimitive.int }).containsExactly(4)
            assertThat(first["client_id"]).isNotEqualTo(second["client_id"])
        }

    @Test
    fun payload_meta_has_annotates_spec_version_page_doc_page_lines() =
        runBlocking<Unit> {
            send(listOf(input(0), input(3)), version = 2, latest = 2)
            runner().drain()
            val meta = metaOf(double.requests.single())
            assertThat(meta["conversation_id"]!!.jsonPrimitive.content).isEqualTo("srv-1")
            val annotates = meta["annotates"]!!.jsonObject
            assertThat(annotates["spec_version"]!!.jsonPrimitive.int).isEqualTo(2)
            val pages = annotates["pages"]!!.jsonArray.map { it.jsonObject }
            assertThat(pages.map { it["page"]!!.jsonPrimitive.int }).containsExactly(0, 3).inOrder()
            assertThat(pages.map { it["doc_page"]!!.jsonPrimitive.int }).containsExactly(0, 3).inOrder()
            assertThat(pages[1]["lines"]!!.jsonArray.map { it.jsonPrimitive.int }).containsExactly(30, 39).inOrder()
        }

    @Test
    fun caption_default_and_with_user_text() =
        runBlocking<Unit> {
            send(listOf(input(0)), version = 3, latest = 3)
            assertThat(
                db
                    .threadDao()
                    .list("c1")
                    .single()
                    .text,
            ).isEqualTo("Handwritten notes over spec v3")
            assertThat(AnnotationSend.caption(3, "  looks off ")).isEqualTo("Handwritten notes over spec v3 looks off")
            assertThat(AnnotationSend.caption(3, "  ")).isEqualTo("Handwritten notes over spec v3")
        }

    @Test
    fun double_tap_queues_once() =
        runBlocking<Unit> {
            val outcomes = (1..2).map { async { send(listOf(input(0))) } }.awaitAll()
            assertThat(outcomes.count { it is SendOutcome.Queued }).isEqualTo(1)
            assertThat(outcomes).contains(SendOutcome.AlreadySent)
            assertThat(db.outboxDao().forConversation("c1")).hasSize(1)
        }

    @Test
    fun replay_after_a_dropped_connection_sends_the_same_client_id() =
        runBlocking<Unit> {
            send(listOf(input(0)))
            double.uploadStatus = 503
            runner().drain()
            double.uploadStatus = 200
            val row = db.outboxDao().forConversation("c1").single()
            db.outboxDao().update(row.copy(nextAttemptAtMs = 0))
            runner().drain()
            val (before, after) = double.requests.map(::metaOf)
            assertThat(after).isEqualTo(before)
        }

    @Test
    fun export_too_big_returns_export_failed_and_draft_stays_unsent() =
        runBlocking<Unit> {
            exporter = { ExportResult.TooBig("too big") }
            assertThat(send(listOf(input(0)))).isInstanceOf(SendOutcome.ExportFailed::class.java)
            assertThat(store.isSent("c1", 1)).isFalse()
            assertThat(db.outboxDao().forConversation("c1")).isEmpty()
        }

    @Test
    fun marks_draft_sent_and_view_becomes_read_only() =
        runBlocking<Unit> {
            send(listOf(input(0)))
            assertThat(store.isSent("c1", 1)).isTrue()
        }

    @Test
    fun permanent_outbox_failure_returns_the_draft_to_editable() =
        runBlocking<Unit> {
            send(listOf(input(0)), version = 2, latest = 2)
            assertThat(store.isSent("c1", 2)).isTrue()
            double.uploadStatus = 413
            assertThat(runner().drain().failed).isEqualTo(1)
            assertThat(store.isSent("c1", 2)).isFalse()
        }

    @Test
    fun retryable_failure_keeps_the_draft_sent() =
        runBlocking<Unit> {
            send(listOf(input(0)))
            double.uploadStatus = 503
            assertThat(runner().drain().failed).isEqualTo(0)
            assertThat(store.isSent("c1", 1)).isTrue()
        }

    @Test
    fun nothing_inked_is_nothing_to_send() =
        runBlocking<Unit> {
            assertThat(send(listOf(input(0, inked = false)))).isEqualTo(SendOutcome.NothingToSend)
            assertThat(store.isSent("c1", 1)).isFalse()
        }

    private companion object {
        const val PNG_SIZE = 100
    }
}
