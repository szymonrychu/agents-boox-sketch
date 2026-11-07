package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
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
import io.github.szymonrychu.booxsketch.model.notebook.OpKind
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import io.github.szymonrychu.booxsketch.render.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

/** Send with image regions (Task 6.2): region crops as parts, image ops in the layout, member ink kept out of segments. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendUseCaseRegionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val g = NotebookGeometry(CANVAS.w, CANVAS.h)
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private lateinit var repo: ConversationRepository
    private lateinit var board: FakeBoard
    private lateinit var useCase: SendUseCase
    private val requests = mutableListOf<RecordedRequest>()
    private val crops = mutableListOf<String>()
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

    /** An image region over rows [fromRow] until [toRow], x 100 to 700. */
    private fun region(
        id: String,
        fromRow: Int,
        toRow: Int,
    ) = Stroke(
        id,
        Tool.IMAGE_REGION,
        3f,
        listOf(Point(100f, g.rowTop(fromRow).toFloat(), 1f, 0L), Point(700f, g.rowTop(toRow).toFloat(), 1f, 1L)),
    )

    private fun run(
        lineId: String,
        row: Int,
    ) = SourceMap.stripWithColours("x").let {
        TextRun(lineId, 3, 1, row, 53f, display = it.display, colourSpans = it.spans, sourceMap = it.map, sourceStrokeIds = emptyList())
    }

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
                            return MockResponse()
                                .setResponseCode(202)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"sketch_id":"sk-1","conversation_id":"srv-1","status":"queued"}""")
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
                context.getSharedPreferences("region", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                outboxRoot,
                {},
            )
        board = FakeBoard(Notebook(CANVAS, listOf(Page(emptyList(), true)), 0))
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
                cropRegion = { _, region, _, _, _ ->
                    crops += region.id
                    ByteArray(32) { 7 }
                },
            )
        runBlocking {
            db.conversationDao().upsert(ConversationEntity("local-1", "srv-1", "t", 0, 1))
            repo.select("local-1")
            db.specDao().upsert(
                SpecEntity(
                    "srv-1",
                    3,
                    "x",
                    "2026-11-07T00:00:00Z",
                    layout = """{"lines":[{"line_id":"L1","source_line":1,"origin":null}]}""",
                ),
            )
        }
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
                    .single { it.clientId == clientId }
                    .payloadDir,
            )
        }

    private fun layoutOf(clientId: String): PageLayoutDto =
        Json.decodeFromString(PageLayoutDto.serializer(), File(dir(clientId), "layout.0.json").readText())

    @Test
    fun region_part_and_image_op_written() =
        runBlocking<Unit> {
            board.notebook =
                Notebook(
                    CANVAS,
                    listOf(
                        Page(listOf(region("r", 4, 9), writing("in", 6, 200f, 400f), writing("w", 12)), true, text = listOf(run("L1", 2))),
                    ),
                    0,
                )
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val op = layoutOf(clientId).ops.single()
            assertThat(op.kind).isEqualTo("image")
            assertThat(op.region!!.part).isEqualTo("region.0.0.png")
            assertThat(op.region!!.afterLine).isEqualTo("L1")
            assertThat(op.rect).containsExactly(100f, g.rowTop(4).toFloat(), 600f, (g.rowTop(9) - g.rowTop(4)).toFloat()).inOrder()
            // strokes.0.json holds r, in, w: the region and the ink inside it.
            assertThat(op.strokes).containsExactly(0, 1)
            assertThat(File(dir(clientId), "region.0.0.png").readBytes()).isEqualTo(ByteArray(32) { 7 })
            assertThat(crops).containsExactly("r")
            // The op waits for a version like the segments do.
            val pending =
                board.notebook.pages[0]
                    .pendingOps
                    .single()
            assertThat(pending.clientId).isEqualTo(clientId)
            assertThat(pending.op.kind).isEqualTo(OpKind.IMAGE)
            assertThat(pending.op.region!!.id).isEqualTo(op.region!!.id)
            assertThat(pending.op.strokeIds).containsExactly("r", "in")
            OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {}).drain()
            val upload = requests.single { it.path.orEmpty().endsWith("/sketches") }
            assertThat(partsOf(upload)).contains("region.0.0.png")
        }

    @Test
    fun ink_inside_region_not_segmented() =
        runBlocking<Unit> {
            board.notebook =
                Notebook(CANVAS, listOf(Page(listOf(region("r", 4, 9), writing("in", 6, 200f, 400f), writing("w", 12)), true)), 0)
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val segments = layoutOf(clientId).segments
            assertThat(segments.map { it.id }).containsExactly("p0-r12-0")
            assertThat(segments.single().strokes).containsExactly(2)
            assertThat(
                board.notebook.pages[0]
                    .pendingSegments
                    .flatMap { it.strokeIds },
            ).containsExactly("w")
        }

    @Test
    fun a_region_already_sent_is_not_sent_again() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(Page(listOf(region("r", 4, 9), writing("in", 6, 200f, 400f)), true)), 0)
            useCase.send(all = false, caption = null)
            board.notebook =
                board.notebook.copy(pages = listOf(board.notebook.pages[0].let { it.copy(strokes = it.strokes + writing("w", 12)) }))
            val second = (useCase.send(all = true, caption = null) as SendOutcome.Queued).clientId
            val layout = layoutOf(second)
            assertThat(layout.ops).isEmpty()
            assertThat(layout.segments.flatMap { it.strokes }).containsExactly(2)
            assertThat(File(dir(second), "region.0.0.png").exists()).isFalse()
            assertThat(crops).containsExactly("r")
        }

    @Test
    fun fifth_region_refused() =
        runBlocking<Unit> {
            val regions = (0 until 5).map { region("r$it", it * 3, it * 3 + 2) }
            board.notebook = Notebook(CANVAS, listOf(Page(regions, true)), 0)
            assertThat(useCase.send(all = false, caption = null)).isEqualTo(SendOutcome.TooManyRegions(0, SendUseCase.MAX_REGIONS_PER_PAGE))
            assertThat(SendUseCase.MAX_REGIONS_PER_PAGE).isEqualTo(4)
            assertThat(db.outboxDao().forConversation("local-1")).isEmpty()
            assertThat(board.notebook.pages[0].changedSinceSend).isTrue()
        }

    @Test
    fun without_a_spec_version_regions_go_as_drawing_without_ops() =
        runBlocking<Unit> {
            db.specDao().deleteFor("srv-1")
            board.notebook =
                Notebook(CANVAS, listOf(Page(listOf(region("r", 4, 9), writing("in", 6, 200f, 400f), writing("w", 12)), true)), 0)
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            // The connector needs base_version for any op: no op, no region part, but the picture is still not transcribed.
            val layout = layoutOf(clientId)
            assertThat(layout.ops).isEmpty()
            assertThat(layout.segments.flatMap { it.strokes }).containsExactly(2)
            assertThat(File(dir(clientId), "region.0.0.png").exists()).isFalse()
            assertThat(board.notebook.pages[0].pendingOps).isEmpty()
        }
}
