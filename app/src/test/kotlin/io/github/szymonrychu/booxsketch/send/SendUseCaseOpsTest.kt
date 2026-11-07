package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.OpTargetDto
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
import io.github.szymonrychu.booxsketch.model.notebook.OpTarget
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextMeasure
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import io.github.szymonrychu.booxsketch.render.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Send with edit ops (Task 5.3): the classifier's ops go into the layout, their strokes into no segment. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendUseCaseOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val g = NotebookGeometry(CANVAS.w, CANVAS.h)
    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepository
    private lateinit var board: FakeBoard
    private lateinit var useCase: SendUseCase
    private var ids = 0

    /** 20 px per char: "buy fresh milk" from x 53 has buy 53-113, fresh 133-233, milk 253-333. */
    private val measure = TextMeasure { text, scale -> text.length * 20f * scale }

    private fun line(
        id: String,
        x0: Float,
        x1: Float,
        y: Float,
    ) = Stroke(id, Tool.BALLPOINT, 2f, (0..10).map { Point(x0 + (x1 - x0) * it / 10, y, 0.5f, it * 10L) })

    private val milk =
        SourceMap.stripWithColours("buy fresh milk").let {
            TextRun("L1", 3, 1, 4, 53f, display = it.display, colourSpans = it.spans, sourceMap = it.map, sourceStrokeIds = emptyList())
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val client = ConnectorClient("https://connector.example/", { "test-token" }, OkHttpClient())
        val outboxRoot = tmp.newFolder("outbox")
        repo =
            ConversationRepository(
                db,
                { client },
                context.getSharedPreferences("ops", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                outboxRoot,
                {},
            )
        // "w" is new writing on row 7, "s" strikes "fresh milk" on row 4.
        val strokes = listOf(line("w", 100f, 300f, g.rowTop(7) + 70f), line("s", 140f, 340f, g.textBandTop(4) + 30f))
        board = FakeBoard(Notebook(CANVAS, listOf(Page(strokes, true, text = listOf(milk))), 0))
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
                measure = measure,
            )
        runBlocking {
            db.conversationDao().upsert(ConversationEntity("local-1", "srv-1", "t", 0, 1))
            repo.select("local-1")
            val layout = """{"lines":[{"line_id":"L1","source_line":1,"origin":null}]}"""
            db.specDao().upsert(SpecEntity("srv-1", 3, "buy fresh milk", "2026-11-07T00:00:00Z", layout = layout))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun dir(clientId: String) =
        runBlocking {
            File(
                db
                    .outboxDao()
                    .forConversation("local-1")
                    .single { it.clientId == clientId }
                    .payloadDir,
            )
        }

    private fun layoutOf(clientId: String): PageLayoutDto =
        Json.decodeFromString(PageLayoutDto.serializer(), File(dir(clientId), "layout.0.json").readText())

    @Test
    fun ops_in_layout_with_stroke_indices() =
        runBlocking<Unit> {
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val op = layoutOf(clientId).ops.single()
            assertThat(op.kind).isEqualTo("delete")
            assertThat(op.target).isEqualTo(OpTargetDto("L1", 4, 14, "fresh milk"))
            // "s" is index 1 of the page's visible strokes (the strokes part).
            assertThat(op.strokes).containsExactly(1)
            assertThat(Payload.readPages(dir(clientId)).baseVersion).isEqualTo(3)
            // The op waits for its version on the board, with the upload's client id.
            val pending =
                board.notebook.pages[0]
                    .pendingOps
                    .single()
            assertThat(pending.clientId).isEqualTo(clientId)
            assertThat(pending.op.id).isEqualTo(op.id)
            assertThat(pending.op.kind).isEqualTo(OpKind.DELETE)
            assertThat(pending.op.target).isEqualTo(OpTarget("L1", 4, 14, "fresh milk"))
            assertThat(pending.op.strokeIds).containsExactly("s")
        }

    @Test
    fun op_strokes_not_segmented() =
        runBlocking<Unit> {
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            val layout = layoutOf(clientId)
            assertThat(layout.segments.map { it.id }).containsExactly("p0-r7-0")
            assertThat(layout.segments.flatMap { it.strokes }).containsExactly(0)
            assertThat(
                board.notebook.pages[0]
                    .pendingSegments
                    .flatMap { it.strokeIds },
            ).containsExactly("w")
        }

    @Test
    fun a_pending_op_is_not_sent_again() =
        runBlocking<Unit> {
            useCase.send(all = false, caption = null)
            board.notebook = board.notebook.copy(pages = board.notebook.pages.map { it.copy(changedSinceSend = true) })
            val again = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            assertThat(layoutOf(again).ops).isEmpty()
            assertThat(layoutOf(again).segments).isEmpty()
        }

    @Test
    fun runs_unknown_to_the_base_get_no_ops() =
        runBlocking<Unit> {
            db.specDao().upsert(SpecEntity("srv-1", 4, "other", "2026-11-07T00:00:00Z", layout = """{"lines":[]}"""))
            val clientId = (useCase.send(all = false, caption = null) as SendOutcome.Queued).clientId
            assertThat(layoutOf(clientId).ops).isEmpty()
            // L1 is not in the base version: no op may target it.
            assertThat(board.notebook.pages[0].pendingOps).isEmpty()
        }
}
