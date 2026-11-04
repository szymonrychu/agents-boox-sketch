package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.HandoffDto
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.OutboxEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.StrokesCodec
import io.github.szymonrychu.booxsketch.render.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendUseCaseTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var double: ConnectorDouble
    private lateinit var repo: ConversationRepository
    private lateinit var board: FakeBoard
    private lateinit var useCase: SendUseCase
    private lateinit var outboxRoot: File
    private lateinit var client: ConnectorClient

    private var clock = 1_000L
    private var ids = 0
    private var convIds = 0
    private var kicks = 0
    private var limits = ConfigCache.DEFAULT
    private var exporter: (
        Page,
        Canvas,
        ConfigDto,
    ) -> ExportResult = { _, _, _ -> ExportResult.Exported(ByteArray(PNG_SIZE) { 1 }, 300, 400) }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        double = ConnectorDouble()
        server = MockWebServer().apply { dispatcher = double }
        server.start()
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        outboxRoot = File(tmp.root, "outbox")
        repo =
            ConversationRepository(
                db,
                { client },
                context.getSharedPreferences("send-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                outboxRoot,
                onUnauthorized = {},
                clock = { clock },
                newId = { "conv-${++convIds}" },
            )
        board = FakeBoard(Notebook(CANVAS, listOf(page(true, "a")), 0))
        useCase =
            SendUseCase(
                db = db,
                conversations = repo,
                board = board,
                export = { p, c, cfg -> exporter(p, c, cfg) },
                limits = { limits },
                outboxRoot = outboxRoot,
                kick = { kicks++ },
                worker = Dispatchers.Unconfined,
                clock = { clock },
                newId = { "00000000-0000-4000-8000-00000000000${++ids}" },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun runner() = OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {}, clock = { clock })

    private fun outbox(): List<OutboxEntity> = runBlocking { db.outboxDao().forConversation(repo.current().localId) }

    private fun conversation(
        localId: String,
        serverId: String?,
        lastActivityMs: Long,
    ) = runBlocking { db.conversationDao().upsert(ConversationEntity(localId, serverId, "t-$localId", 0, lastActivityMs)) }

    private fun outboxFiles() = outboxRoot.walk().filter { it.isFile }.toList()

    @Test
    fun send_writes_payload_and_row_atomically() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(page(true, "a"), page(true, "b")), 0)
            val outcome = useCase.send(all = false, caption = null)
            val clientId = (outcome as SendOutcome.Queued).clientId

            val rows = outbox()
            assertThat(rows).hasSize(1)
            val row = rows.single()
            assertThat(row.clientId).isEqualTo(clientId)
            assertThat(row.kind).isEqualTo(OutboxKind.SKETCH)
            assertThat(row.state).isEqualTo(OutboxState.PENDING)
            assertThat(row.conversationLocalId).isEqualTo(repo.selected.value)
            assertThat(row.conversationServerId).isNull()
            assertThat(row.conversationMode).isNull()
            val dir = File(row.payloadDir)
            assertThat(dir.parentFile).isEqualTo(outboxRoot)
            assertThat(dir.list()!!.toList())
                .containsExactly("sketch.0.png", "sketch.1.png", "strokes.0.json", "strokes.1.json", "pages.json")
            val pages = Json.parseToJsonElement(File(dir, "pages.json").readText()).jsonObject
            assertThat(pages["client_id"]!!.jsonPrimitive.content).isEqualTo(clientId)
            assertThat(pages.keys).containsNoneOf("conversation", "conversation_id")
            val pageList = pages["pages"]!!.jsonArray.map { it.jsonObject }
            assertThat(pageList.map { it["page"]!!.jsonPrimitive.int }).containsExactly(0, 1).inOrder()
            assertThat(pageList[0]["canvas"]!!.jsonObject["w"]!!.jsonPrimitive.int).isEqualTo(CANVAS.w)
            assertThat(StrokesCodec.decode(File(dir, "strokes.1.json").readBytes()).second).hasSize(1)
            val thread = db.threadDao().list(row.conversationLocalId).single()
            assertThat(thread.clientId).isEqualTo(clientId)
            assertThat(thread.kind).isEqualTo(ThreadKind.SKETCH)
            assertThat(thread.pageCount).isEqualTo(2)
            assertThat(thread.status).isEqualTo(ThreadStatus.QUEUED)
            assertThat(board.notebook.pages.map { it.changedSinceSend }).containsExactly(false, false)
            assertThat(kicks).isEqualTo(1)

            // A failing transaction (thread insert aborted) leaves neither a row nor a payload.
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_thread BEFORE INSERT ON thread_items BEGIN SELECT RAISE(ABORT, 'boom'); END",
            )
            board.notebook = board.notebook.copy(pages = listOf(page(true, "a", "c"), board.notebook.pages[1]))
            assertThrows(Exception::class.java) { runBlocking { useCase.send(all = false, caption = null) } }
            assertThat(outbox()).hasSize(1)
            assertThat(outboxRoot.list()!!.toList()).containsExactly(clientId)
            assertThat(board.notebook.pages[0].changedSinceSend).isTrue()
            assertThat(kicks).isEqualTo(1)
        }

    @Test
    fun empty_pages_never_sent() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(page(true, "a"), page(true), page(false, "c")), 0)
            val all = useCase.send(all = true, caption = null) as SendOutcome.Queued
            val pages = Payload.readPages(File(outboxRoot, all.clientId)).pages
            assertThat(pages.map { it.page }).containsExactly(0, 2).inOrder()
            // Nothing changed since: a tap sends nothing (the empty page is not "changed content").
            assertThat(useCase.send(all = false, caption = null)).isEqualTo(SendOutcome.NothingToSend)
        }

    @Test
    fun export_empty_page_is_skipped() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(page(true, "a"), page(true, "b")), 0)
            exporter =
                { p, _, _ -> if (p.strokes.first().id == "a") ExportResult.Empty else ExportResult.Exported(ByteArray(PNG_SIZE), 300, 400) }
            val queued = useCase.send(all = false, caption = null) as SendOutcome.Queued
            assertThat(Payload.readPages(File(outboxRoot, queued.clientId)).pages.map { it.page }).containsExactly(1)
        }

    @Test
    fun too_many_pages_refused_before_outbox() =
        runBlocking<Unit> {
            limits = ConfigCache.DEFAULT.copy(maxPages = 1)
            board.notebook = Notebook(CANVAS, listOf(page(true, "a"), page(true, "b")), 0)
            assertThat(useCase.send(all = false, caption = null)).isEqualTo(SendOutcome.TooManyPages(2, 1))
            assertThat(outboxFiles()).isEmpty()
            assertThat(db.conversationDao().all()).isEmpty()
            assertThat(board.notebook.pages.all { it.changedSinceSend }).isTrue()
            assertThat(kicks).isEqualTo(0)
        }

    @Test
    fun too_large_total_refused_before_outbox() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(page(true, "a"), page(true, "b")), 0)
            val strokes = board.notebook.pages.sumOf { StrokesCodec.encode(CANVAS, it).size }
            val total = 2L * PNG_SIZE + strokes
            limits = ConfigCache.DEFAULT.copy(maxUploadBytes = (total - 1).toInt())
            assertThat(useCase.send(all = false, caption = null)).isEqualTo(SendOutcome.TooLarge(total, total - 1))
            assertThat(outboxFiles()).isEmpty()
            assertThat(db.conversationDao().all()).isEmpty()
            assertThat(board.notebook.pages.all { it.changedSinceSend }).isTrue()
            assertThat(kicks).isEqualTo(0)
            assertThat(sendOutcomeText(SendOutcome.TooLarge(total, total - 1))).startsWith("Not sent")
        }

    @Test
    fun oversized_strokes_are_omitted_but_png_sent() =
        runBlocking<Unit> {
            limits = ConfigCache.DEFAULT.copy(maxStrokesBytes = 10)
            val queued = useCase.send(all = false, caption = null) as SendOutcome.Queued
            assertThat(File(outboxRoot, queued.clientId).list()!!.toList()).containsExactly("sketch.0.png", "pages.json")
            runner().drain()
            assertThat(partsOf(double.requests.single())).containsExactly("sketch.0.png")
        }

    @Test
    fun export_too_big_returns_export_failed_nothing_written() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(page(true, "a"), page(true, "b")), 0)
            exporter = { p, _, _ ->
                if (p.strokes.first().id ==
                    "b"
                ) {
                    ExportResult.TooBig("png too big")
                } else {
                    ExportResult.Exported(ByteArray(PNG_SIZE), 300, 400)
                }
            }
            assertThat(useCase.send(all = false, caption = null)).isEqualTo(SendOutcome.ExportFailed(1, "png too big"))
            assertThat(outboxFiles()).isEmpty()
            assertThat(db.conversationDao().all()).isEmpty()
            assertThat(board.notebook.pages.all { it.changedSinceSend }).isTrue()
        }

    @Test
    fun send_uses_selected_conversation_id() =
        runBlocking<Unit> {
            conversation("old", "srv-old", lastActivityMs = 1)
            conversation("new", "srv-new", lastActivityMs = 2)
            repo.select("old")
            double.conversationId = "srv-old"
            useCase.send(all = false, caption = null)
            assertThat(runner().drain().accepted).isEqualTo(1)
            val meta = metaOf(double.requests.single())
            assertThat(meta["conversation_id"]!!.jsonPrimitive.content).isEqualTo("srv-old")
            assertThat(meta["conversation"]!!.jsonPrimitive.content).isEqualTo("current")
            assertThat(
                db
                    .outboxDao()
                    .forConversation("old")
                    .single()
                    .conversationServerId,
            ).isEqualTo("srv-old")
            assertThat(db.outboxDao().forConversation("new")).isEmpty()
        }

    @Test
    fun first_send_of_new_conversation_uses_new_mode() =
        runBlocking<Unit> {
            conversation("older", "srv-older", lastActivityMs = 1)
            val local = repo.newConversation()
            useCase.send(all = false, caption = null)
            assertThat(runner().drain().accepted).isEqualTo(1)
            val meta = metaOf(double.requests.single())
            assertThat(meta["conversation"]!!.jsonPrimitive.content).isEqualTo("new")
            assertThat(meta.containsKey("conversation_id")).isFalse()
            assertThat(db.conversationDao().get(local)!!.serverId).isEqualTo("srv-1")
            val item = db.outboxDao().forConversation(local).single()
            assertThat(item.state).isEqualTo(OutboxState.ACCEPTED)
            assertThat(item.serverId).isEqualTo("sk-1")
            assertThat(item.conversationMode).isEqualTo("new")
            assertThat(
                db
                    .threadDao()
                    .list(local)
                    .single()
                    .status,
            ).isEqualTo(ThreadStatus.SENT)
        }

    @Test
    fun caption_written_to_meta() =
        runBlocking<Unit> {
            val queued = useCase.send(all = false, caption = "a login form") as SendOutcome.Queued
            runner().drain()
            assertThat(Payload.readMeta(File(outboxRoot, queued.clientId)).caption).isEqualTo("a login form")
            assertThat(metaOf(double.requests.single())["caption"]!!.jsonPrimitive.content).isEqualTo("a login form")
            // An unnamed conversation is named after its first caption.
            assertThat(repo.current().title).isEqualTo("a login form")
        }

    @Test
    fun two_offline_sends_one_new_conversation() =
        runBlocking<Unit> {
            val local = repo.newConversation()
            useCase.send(all = false, caption = null)
            board.notebook = board.notebook.copy(pages = listOf(page(true, "a", "b")))
            useCase.send(all = false, caption = null)
            assertThat(runner().drain().accepted).isEqualTo(2)
            val (first, second) = double.requests.map(::metaOf)
            assertThat(first["conversation"]!!.jsonPrimitive.content).isEqualTo("new")
            assertThat(first.containsKey("conversation_id")).isFalse()
            assertThat(second["conversation_id"]!!.jsonPrimitive.content).isEqualTo("srv-1")
            assertThat(db.conversationDao().get(local)!!.serverId).isEqualTo("srv-1")
        }

    @Test
    fun backing_off_upload_is_not_overtaken_in_its_conversation() =
        runBlocking<Unit> {
            repo.newConversation()
            useCase.send(all = false, caption = null)
            board.notebook = board.notebook.copy(pages = listOf(page(true, "a", "b")))
            useCase.send(all = false, caption = null)
            double.uploadStatus = 503
            val scheduled = mutableListOf<Long>()
            val runner = OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = { scheduled += it }, clock = { clock })
            runner.drain()
            assertThat(double.requests).hasSize(1)
            assertThat(scheduled.single()).isAtLeast(1_000L)
            double.uploadStatus = 202
            clock += 600_000
            assertThat(runner.drain().accepted).isEqualTo(2)
            val metas = double.requests.map(::metaOf)
            assertThat(metas.map { it["client_id"]!!.jsonPrimitive.content }.distinct()).hasSize(2)
            assertThat(metas[2]["conversation_id"]!!.jsonPrimitive.content).isEqualTo("srv-1")
        }

    @Test
    fun crash_after_payload_resends_same_client_id_and_payload_and_replay_counts_as_accepted() =
        runBlocking<Unit> {
            val queued = useCase.send(all = false, caption = "x") as SendOutcome.Queued
            double.uploadStatus = 503
            runner().drain()
            // Process death while the retry was in flight: the row is left SENDING.
            val row = db.outboxDao().forConversation(repo.current().localId).single()
            db.outboxDao().update(row.copy(state = OutboxState.SENDING, nextAttemptAtMs = 0))
            double.uploadStatus = 200
            val summary = runner().drain()
            assertThat(summary.accepted).isEqualTo(1)
            val (before, after) = double.requests
            assertThat(metaOf(after)).isEqualTo(metaOf(before))
            assertThat(metaOf(after)["client_id"]!!.jsonPrimitive.content).isEqualTo(queued.clientId)
            assertThat(after.body.clone().size).isEqualTo(before.body.clone().size)
            assertThat(db.outboxDao().get(row.id)!!.state).isEqualTo(OutboxState.ACCEPTED)
        }

    @Test
    fun send_message_queues_message_item() =
        runBlocking<Unit> {
            conversation("c", "srv-1", lastActivityMs = 1)
            repo.select("c")
            assertThat(useCase.sendMessage("   ")).isEqualTo(SendOutcome.NothingToSend)
            val queued = useCase.sendMessage("make the button blue") as SendOutcome.Queued
            val row = db.outboxDao().forConversation("c").single()
            assertThat(row.kind).isEqualTo(OutboxKind.MESSAGE)
            assertThat(row.clientId).isEqualTo(queued.clientId)
            assertThat(Payload.readMessage(File(row.payloadDir)).text).isEqualTo("make the button blue")
            val thread = db.threadDao().list("c").single()
            assertThat(thread.kind).isEqualTo(ThreadKind.FEEDBACK)
            assertThat(thread.text).isEqualTo("make the button blue")
            assertThat(runner().drain().accepted).isEqualTo(1)
            val request = double.requests.single()
            assertThat(request.path).isEqualTo("/app/v1/sketch/conversations/srv-1/messages")
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertThat(body["client_id"]!!.jsonPrimitive.content).isEqualTo(queued.clientId)
        }

    @Test
    fun message_in_unsent_conversation_creates_it_first() =
        runBlocking<Unit> {
            val local = repo.newConversation()
            useCase.sendMessage("hello")
            runner().drain()
            assertThat(double.requests.map { it.method + " " + it.path })
                .containsExactly("POST /app/v1/sketch/conversations", "POST /app/v1/sketch/conversations/srv-1/messages")
                .inOrder()
            assertThat(db.conversationDao().get(local)!!.serverId).isEqualTo("srv-1")
        }

    @Test
    fun enqueue_handoff_queues_item_with_spec_version() =
        runBlocking<Unit> {
            conversation("c", "srv-1", lastActivityMs = 1)
            repo.select("c")
            db.specDao().upsert(SpecEntity("srv-1", 2, "# v2", "2026-11-03T00:00:00Z"))
            db.specDao().upsert(SpecEntity("srv-1", 3, "# v3", "2026-11-03T00:01:00Z"))
            val req = HandoffDto.toPreset("00000000-0000-4000-8000-0000000000aa", "coder")
            assertThat(useCase.enqueueHandoff(req)).isEqualTo(SendOutcome.Queued(req.clientId))
            val row = db.outboxDao().forConversation("c").single()
            assertThat(row.kind).isEqualTo(OutboxKind.HANDOFF)
            val file = Payload.readHandoff(File(row.payloadDir))
            assertThat(file.specVersion).isEqualTo(3)
            assertThat(file.req).isEqualTo(req)
            val thread = db.threadDao().list("c").single()
            assertThat(thread.kind).isEqualTo(ThreadKind.HANDOFF)
            assertThat(thread.text).contains("spec v3")
            assertThat(runner().drain().accepted).isEqualTo(1)
            val request = double.requests.single()
            assertThat(request.path).isEqualTo("/app/v1/sketch/conversations/srv-1/handoff")
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertThat(body["session"]!!.jsonObject["preset"]!!.jsonPrimitive.content).isEqualTo("coder")
        }

    @Test
    fun stroke_drawn_during_send_keeps_its_page_changed() =
        runBlocking<Unit> {
            val drawnMeanwhile = page(true, "a", "late")
            exporter = { p, _, _ ->
                board.notebook = board.notebook.copy(pages = listOf(drawnMeanwhile))
                ExportResult.Exported(ByteArray(PNG_SIZE), 300, 400).takeIf { p.strokes.isNotEmpty() } ?: ExportResult.Empty
            }
            useCase.send(all = false, caption = null)
            assertThat(
                board.notebook.pages
                    .single()
                    .changedSinceSend,
            ).isTrue()
        }

    private companion object {
        const val PNG_SIZE = 100
    }
}
