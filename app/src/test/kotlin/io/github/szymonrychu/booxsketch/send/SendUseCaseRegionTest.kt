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
import io.github.szymonrychu.booxsketch.model.notebook.TextMeasure
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
    private var cfg = ConfigCache.DEFAULT

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
                limits = { cfg },
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
            spec(3, """{"lines":[{"line_id":"L1","source_line":1,"origin":null}]}""")
        }
    }

    private suspend fun spec(
        version: Int,
        layout: String,
    ) = db.specDao().upsert(SpecEntity("srv-1", version, "x", "2026-11-07T00:00:00Z", layout = layout))

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

    @Test
    fun region_sent_as_op_after_first_version_without_other_changes() =
        runBlocking<Unit> {
            db.specDao().deleteFor("srv-1")
            board.notebook = Notebook(CANVAS, listOf(Page(listOf(region("r", 4, 9), writing("in", 6, 200f, 400f)), true)), 0)
            useCase.send(all = false, caption = null)
            assertThat(board.notebook.pages[0].changedSinceSend).isFalse()
            // A version now exists: the region waiting as plain drawing is sent as its image op, though nothing else changed.
            spec(1, """{"lines":[{"line_id":"L1","source_line":1,"origin":null}]}""")
            val second = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            assertThat(layoutOf(second).ops.single().kind).isEqualTo("image")
            // And once the op is pending, nothing is left to send.
            assertThat(useCase.send(all = false, caption = null)).isEqualTo(SendOutcome.NothingToSend)
        }

    @Test
    fun five_regions_without_a_spec_version_are_not_refused() =
        runBlocking<Unit> {
            db.specDao().deleteFor("srv-1")
            val regions = (0 until 5).map { region("r$it", it * 3, it * 3 + 2) }
            board.notebook = Notebook(CANVAS, listOf(Page(regions + writing("w", 20), true)), 0)
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            assertThat(File(dir(clientId), "region.0.0.png").exists()).isFalse()
            assertThat(board.notebook.pages[0].pendingOps).isEmpty()
        }

    @Test
    fun after_line_is_the_base_line_ending_nearest_above_the_region() =
        runBlocking<Unit> {
            spec(
                4,
                """{"lines":[{"line_id":"L1","source_line":1,"origin":null},{"line_id":"L2","source_line":2,"origin":null}]}""",
            )
            val page = Page(listOf(region("r", 5, 9)), true, text = listOf(run("L1", 1), run("L2", 3), run("L9", 4), run("L2b", 7)))
            board.notebook = Notebook(CANVAS, listOf(page), 0)
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            // L9 is not in the base version and L2b lies inside the region: the nearest base line above is L2.
            assertThat(
                layoutOf(clientId)
                    .ops
                    .single()
                    .region!!
                    .afterLine,
            ).isEqualTo("L2")
        }

    @Test
    fun after_line_is_null_when_no_base_line_is_above() =
        runBlocking<Unit> {
            board.notebook = Notebook(CANVAS, listOf(Page(listOf(region("r", 1, 3)), true, text = listOf(run("L1", 6)))), 0)
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            assertThat(
                layoutOf(clientId)
                    .ops
                    .single()
                    .region!!
                    .afterLine,
            ).isNull()
        }

    @Test
    fun strokes_over_the_cap_send_the_region_as_drawing_without_an_op() =
        runBlocking<Unit> {
            cfg = ConfigCache.DEFAULT.copy(maxStrokesBytes = 1)
            board.notebook = Notebook(CANVAS, listOf(Page(listOf(region("r", 4, 9), writing("in", 6, 200f, 400f)), true)), 0)
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            assertThat(File(dir(clientId), "region.0.0.png").exists()).isFalse()
            assertThat(board.notebook.pages[0].pendingOps).isEmpty()
        }

    /** 20 px per char, like [SendUseCaseOpsTest]: "buy fresh milk" from x 53 has fresh 133-233 and milk 253-333. */
    private fun milk(
        lineId: String,
        row: Int,
    ) = SourceMap.stripWithColours("buy fresh milk").let {
        TextRun(lineId, 3, 1, row, 53f, display = it.display, colourSpans = it.spans, sourceMap = it.map, sourceStrokeIds = emptyList())
    }

    private fun flatLine(
        id: String,
        y: Float,
    ) = Stroke(id, Tool.BALLPOINT, 2f, (0..10).map { Point(140f + 20f * it, y, 0.5f, it * 10L) })

    @Test
    fun image_region_and_strike_on_one_page_send_both_ops_and_region_ink_is_not_classified() =
        runBlocking<Unit> {
            spec(
                4,
                """{"lines":[{"line_id":"L1","source_line":1,"origin":null},{"line_id":"L2","source_line":2,"origin":null}]}""",
            )
            val classifying =
                SendUseCase(
                    db = db,
                    conversations = repo,
                    board = board,
                    export = { _, _, _ -> ExportResult.Exported(ByteArray(64) { 1 }, 300, 400) },
                    limits = { cfg },
                    outboxRoot = tmp.newFolder("outbox-classify"),
                    kick = {},
                    worker = Dispatchers.Unconfined,
                    newId = { "00000000-0000-4000-8000-00000000000${++ids}" },
                    measure = TextMeasure { text, scale -> text.length * 20f * scale },
                    cropRegion = { _, region, _, _, _ ->
                        crops += region.id
                        ByteArray(32) { 7 }
                    },
                )
            // "s" strikes "fresh milk" of L1 (row 4). The region covers rows 9-12 with L2 on row 10: "in" lies flat over
            // its words like a strike, but it is ink of the picture.
            val strike = flatLine("s", g.textBandTop(4) + 30f)
            val inside = flatLine("in", g.textBandTop(10) + 30f)
            val page = Page(listOf(strike, region("r", 9, 13), inside), true, text = listOf(milk("L1", 4), milk("L2", 10)))
            board.notebook = Notebook(CANVAS, listOf(page), 0)
            val clientId = (classifying.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val ops = layoutOf(clientId).ops
            assertThat(ops.map { it.kind }).containsExactly("delete", "image").inOrder()
            assertThat(ops.first().target!!.lineId).isEqualTo("L1")
            // strokes.0.json holds s, r, in.
            assertThat(ops.first().strokes).containsExactly(0)
            assertThat(ops.last().strokes).containsExactly(1, 2)
            assertThat(layoutOf(clientId).segments).isEmpty()
            assertThat(File(dir(clientId), "region.0.0.png").exists()).isTrue()
            // Both wait for a version in one pipeline: same upload, stamped with the base version.
            val pending = board.notebook.pages[0].pendingOps
            assertThat(pending.map { it.op.kind }).containsExactly(OpKind.DELETE, OpKind.IMAGE).inOrder()
            assertThat(pending.map { it.clientId }.toSet()).containsExactly(clientId)
            assertThat(pending.map { it.baseVersion }.toSet()).containsExactly(4)
            assertThat(pending.first().op.strokeIds).containsExactly("s")
            assertThat(pending.last().op.strokeIds).containsExactly("r", "in")
        }
}
