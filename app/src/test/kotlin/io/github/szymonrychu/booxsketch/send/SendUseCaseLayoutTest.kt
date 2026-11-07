package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.OutboxReasons
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.api.PageLayoutDto
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.render.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Send with notebook layouts (Task 4.6): segments, pending ticks, base_version, and the outbox upload of the parts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendUseCaseLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    private val g = NotebookGeometry(CANVAS.w, CANVAS.h)
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private lateinit var repo: ConversationRepository
    private lateinit var board: FakeBoard
    private lateinit var useCase: SendUseCase
    private val requests = mutableListOf<RecordedRequest>()
    private var uploadAnswer: MockResponse =
        MockResponse().setResponseCode(202).setBody("""{"sketch_id":"sk-1","conversation_id":"srv-1","status":"queued"}""")
    private var ids = 0

    private fun writing(
        id: String,
        row: Int,
        x0: Float = 100f,
        x1: Float = 300f,
    ): Stroke {
        val y = g.rowTop(row) + 70f
        return Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(x0, y, 0.5f, 0L), Point(x1, y, 0.5f, 10L)))
    }

    /** A drawing stroke: three rows tall. */
    private fun drawing(id: String) = Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(200f, 900f, 0.5f, 0L), Point(260f, 1300f, 0.5f, 10L)))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            requests += request
                            return uploadAnswer.clone().setHeader("Content-Type", "application/json")
                        }
                    }
            }
        server.start()
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        val outboxRoot = tmp.newFolder("outbox")
        repo =
            ConversationRepository(
                db,
                { client },
                context.getSharedPreferences("layout", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                outboxRoot,
                {},
            )
        board = FakeBoard(Notebook(CANVAS, listOf(Page(listOf(writing("a", 4), writing("b", 4, 320f, 400f), drawing("d")), true)), 0))
        useCase =
            SendUseCase(
                db = db,
                conversations = repo,
                board = board,
                export = { _, _, _ -> ExportResult.Exported(ByteArray(64) { 1 }, 300, 400) },
                limits = { ConfigCache.DEFAULT },
                outboxRoot = outboxRoot,
                kick = {},
                worker = Dispatchers.Unconfined,
                newId = { "00000000-0000-4000-8000-00000000000${++ids}" },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun dir(clientId: String) =
        runBlocking {
            File(
                db
                    .outboxDao()
                    .forConversation(repo.current().localId)
                    .single {
                        it.clientId ==
                            clientId
                    }.payloadDir,
            )
        }

    private fun layoutOf(clientId: String): PageLayoutDto =
        Json.decodeFromString(PageLayoutDto.serializer(), File(dir(clientId), "layout.0.json").readText())

    @Test
    fun layout_part_written_with_segments_and_base_version() =
        runBlocking<Unit> {
            db.conversationDao().upsert(ConversationEntity("local-1", "srv-1", "t", 0, 1))
            repo.select("local-1")
            db.specDao().upsert(SpecEntity("srv-1", 3, "x", "2026-11-07T00:00:00Z"))
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val layout = layoutOf(clientId)
            assertThat(layout.segments.map { it.id }).containsExactly("p0-r4-0")
            assertThat(layout.segments.single().strokes).containsExactly(0, 1).inOrder()
            assertThat(layout.geometry.rowPitch).isEqualTo(g.rowPitch)
            val pages = Json.parseToJsonElement(File(dir(clientId), Payload.PAGES).readText()).jsonObject
            assertThat(pages["base_version"]!!.jsonPrimitive.int).isEqualTo(3)
            assertThat(Payload.readPages(dir(clientId)).baseVersion).isEqualTo(3)
        }

    @Test
    fun pending_segments_recorded_with_client_id() =
        runBlocking<Unit> {
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val pending = board.notebook.pages[0].pendingSegments
            assertThat(pending).containsExactly(PendingSegment(clientId, "p0-r4-0", 4, 100f, 400f, listOf("a", "b")))
            // Sent and pending: the page is not changed any more.
            assertThat(board.notebook.pages[0].changedSinceSend).isFalse()
        }

    @Test
    fun drawing_strokes_not_segmented() =
        runBlocking<Unit> {
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val strokes = layoutOf(clientId).segments.flatMap { it.strokes }
            // "d" is index 2 of the strokes part: drawing ink, in no segment.
            assertThat(strokes).doesNotContain(2)
            assertThat(
                board.notebook.pages[0]
                    .pendingSegments
                    .flatMap { it.strokeIds },
            ).doesNotContain("d")
        }

    @Test
    fun a_page_without_handwriting_rows_has_no_layout_part() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(Page(listOf(drawing("d")), true)), 0)
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            assertThat(File(dir(clientId), "layout.0.json").exists()).isFalse()
        }

    @Test
    fun a_page_with_only_consumed_strokes_is_not_sent() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(Page(listOf(writing("a", 4)), true, consumed = setOf("a"))), 0)
            assertThat(useCase.send(all = true, caption = null)).isEqualTo(SendOutcome.NothingToSend)
        }

    @Test
    fun upload_sends_layout_parts() =
        runBlocking<Unit> {
            useCase.send(all = false, caption = null)
            OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {}).drain()
            val upload = requests.single { it.path.orEmpty().endsWith("/sketches") }
            assertThat(partsOf(upload)).contains("layout.0.json")
            assertThat(upload.body.clone().readUtf8()).contains("\"p0-r4-0\"")
        }

    @Test
    fun stale_base_fails_item_and_syncs() =
        runBlocking<Unit> {
            uploadAnswer = MockResponse().setResponseCode(409).setBody("""{"error":"stale_base","detail":"spec version 4 is the latest"}""")
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            var synced = 0
            val failed = mutableListOf<String>()
            val summary =
                OutboxRunner(
                    db,
                    { client },
                    onNeedsPairing = {},
                    schedule = {},
                    onFailed = { failed += it.clientId },
                    onStaleBase = { synced++ },
                ).drain()
            assertThat(summary.failed).isEqualTo(1)
            val item = db.outboxDao().forConversation(repo.current().localId).single()
            assertThat(item.state).isEqualTo(OutboxState.FAILED_PERMANENT)
            assertThat(item.lastError).isEqualTo(OutboxReasons.STALE_BASE)
            assertThat(
                db
                    .threadDao()
                    .list(repo.current().localId)
                    .single()
                    .status,
            ).isEqualTo(ThreadStatus.failed(OutboxReasons.STALE_BASE))
            assertThat(synced).isEqualTo(1)
            assertThat(failed).containsExactly(clientId)
        }
}
