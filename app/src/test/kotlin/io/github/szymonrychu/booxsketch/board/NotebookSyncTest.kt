package io.github.szymonrychu.booxsketch.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.data.ThreadItemEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.EditOp
import io.github.szymonrychu.booxsketch.model.notebook.OpKind
import io.github.szymonrychu.booxsketch.model.notebook.OpTarget
import io.github.szymonrychu.booxsketch.model.notebook.PendingOp
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NotebookSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private val g = NotebookGeometry(1404, 1872)
    private val canvas = Canvas(1404, 1872, 300f)

    /** Records each swap and whether raw drawing was off (a hold outstanding) when it happened. */
    private class RecordingSurface(
        private val input: FreezingInput,
    ) : TextLayerSurface {
        val swaps = mutableListOf<Pair<Bitmap?, Boolean>>()
        var dirty: RectF? = null

        override fun swapText(
            strokes: List<Stroke>,
            textLayer: Bitmap?,
            dirtyPage: RectF?,
        ) {
            swaps += textLayer to (input.held > 0)
            dirty = dirtyPage
        }
    }

    /** Records each uiChanged with whether raw drawing was off and how many swaps had happened by then. */
    private class RecordingUi(
        private val input: FreezingInput,
        private val surface: RecordingSurface,
    ) : UiRefresh {
        val calls = mutableListOf<Pair<Boolean, Int>>()

        override fun uiChanged(settled: Boolean) {
            calls += (input.held > 0) to surface.swaps.size
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun stroke(
        id: String,
        row: Int,
    ): Stroke {
        val y = g.rowTop(row) + 70f
        return Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(100f, y, 0.5f, 0L), Point(300f, y, 0.5f, 10L)))
    }

    private val layout =
        """{"lines":[{"line_id":"L1","source_line":1,"origin":{"segments":["p0-r4-0"]}}],"ops":[],"base_version":null}"""

    private fun TestScope.board(bound: String? = null): BoardViewModel {
        val store = NotebookStore(File(tmp.root, "n.json"))
        val pending = listOf(PendingSegment("c1", "p0-r4-0", 4, 100f, 300f, listOf("a", "b")))
        val page = Page(listOf(stroke("a", 4), stroke("b", 4)), false, pendingSegments = pending)
        store.save(Notebook(canvas, listOf(page), 0, conversationLocalId = bound))
        return BoardViewModel(store, this, StandardTestDispatcher(testScheduler), canvas)
    }

    private suspend fun storeVersion() = db.specDao().upsert(SpecEntity("srv", 1, "buy milk", "2026-11-07T00:00:00Z", "[]", layout))

    @Test
    fun version_applies_runs_and_consumes() =
        runTest {
            storeVersion()
            val vm = board()
            val input = FreezingInput()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = input }
            val surface = RecordingSurface(input).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sync = NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher)
            advanceTimeBy(1_000)
            sync.onVersion("srv", "local", 1)
            val page = vm.notebook.value.pages[0]
            assertThat(page.text.single().display).isEqualTo("buy milk")
            assertThat(page.consumed).containsExactly("a", "b")
            assertThat(page.pendingSegments).isEmpty()
            assertThat(vm.notebook.value.appliedSpecVersion).isEqualTo(1)
            assertThat(surface.swaps).hasSize(1)
            assertThat(surface.swaps.single().first).isNotNull()
            // The whole page is drawn again, not only the rows that changed.
            assertThat(surface.dirty).isNull()
            // The cached layer is the swapped one: the next board frame finds it and does not redraw.
            assertThat(vm.textLayers.bitmapFor(page)).isSameInstanceAs(surface.swaps.single().first)
            // A version already applied is not applied again.
            sync.onVersion("srv", "local", 1)
            assertThat(surface.swaps).hasSize(1)
            assertThat(input.held).isEqualTo(0)
        }

    @Test
    fun version_swap_requests_ui_refresh_while_held() =
        runTest {
            storeVersion()
            val vm = board()
            val input = FreezingInput()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = input }
            val surface = RecordingSurface(input).also { holds.surface = it }
            val ui = RecordingUi(input, surface).also { holds.ui = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sync = NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher)
            advanceTimeBy(1_000)
            sync.onVersion("srv", "local", 1)
            // One refresh request, made while raw drawing was held off and after the swap.
            assertThat(ui.calls).containsExactly(true to 1)
        }

    @Test
    fun a_version_changing_some_lines_redraws_every_line_at_one_size() =
        runTest {
            storeVersion()
            val vm = board()
            val input = FreezingInput()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = input }
            val surface = RecordingSurface(input).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sync = NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher)
            advanceTimeBy(1_000)
            sync.onVersion("srv", "local", 1)
            // Version 2 keeps L1 as it is and adds L2, a line wider than the text column.
            val long = "a much longer line that does not fit the text column of the page at all"
            val lines = """{"line_id":"L1","source_line":1,"origin":{"kept":"L1"}},{"line_id":"L2","source_line":2,"origin":null}"""
            db.specDao().upsert(SpecEntity("srv", 2, "buy milk\n$long", "2026-11-07T00:00:00Z", "[]", """{"lines":[$lines]}"""))
            sync.onVersion("srv", "local", 2)
            val page = vm.notebook.value.pages[0]
            assertThat(page.text.map { it.lineId }).containsExactly("L1", "L2")
            assertThat(page.text.map { it.scale }.toSet()).hasSize(1)
            assertThat(surface.swaps).hasSize(2)
            // The unchanged line is drawn again with the rest: the whole page, on a new layer.
            assertThat(surface.dirty).isNull()
            assertThat(surface.swaps[1].first).isNotSameInstanceAs(surface.swaps[0].first)
        }

    @Test
    fun version_while_pen_down_applies_after_idle_through_one_hold() =
        runTest {
            storeVersion()
            val vm = board()
            val input = FreezingInput().apply { strokeInProgress = true }
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = input }
            val surface = RecordingSurface(input).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sync = NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher)
            val job = launch { sync.onVersion("srv", "local", 1) }
            advanceTimeBy(5_000)
            runCurrent()
            assertThat(surface.swaps).isEmpty()
            assertThat(input.log).doesNotContain("hold")
            assertThat(
                vm.notebook.value.pages[0]
                    .text,
            ).isEmpty()
            // Pen up: the swap still waits the pen idle time (700 ms) after the last activity.
            input.strokeInProgress = false
            holds.onPenActivity()
            advanceTimeBy(TextLayerHolds.PEN_IDLE_MS - 10)
            runCurrent()
            assertThat(surface.swaps).isEmpty()
            advanceUntilIdle()
            job.join()
            assertThat(surface.swaps).hasSize(1)
            // Raw drawing was held off for the swap, by exactly one hold, released once.
            assertThat(surface.swaps.single().second).isTrue()
            assertThat(input.log.count { it == "hold" }).isEqualTo(1)
            assertThat(input.log.count { it == "release" }).isEqualTo(1)
            assertThat(input.held).isEqualTo(0)
            assertThat(
                vm.notebook.value.pages[0]
                    .text,
            ).hasSize(1)
        }

    @Test
    fun a_stroke_during_the_render_restarts_the_placement() =
        runTest {
            storeVersion()
            val vm = board()
            val input = FreezingInput()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = input }
            RecordingSurface(input).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sync = NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher)
            advanceTimeBy(1_000)
            val job = launch { sync.onVersion("srv", "local", 1) }
            runCurrent()
            vm.onStroke(stroke("late", 8))
            holds.onPenActivity()
            advanceUntilIdle()
            job.join()
            val page = vm.notebook.value.pages[0]
            // The stroke drawn meanwhile is kept and the version still applied.
            assertThat(page.strokes.map { it.id }).contains("late")
            assertThat(page.visibleStrokes.map { it.id }).containsExactly("late")
            assertThat(page.text).hasSize(1)
        }

    @Test
    fun version_without_layout_leaves_the_board() =
        runTest {
            db.specDao().upsert(SpecEntity("srv", 1, "typed", "2026-11-07T00:00:00Z", "[]", null))
            val vm = board()
            // Nothing pending (a version without a layout releases pending marks, see below).
            vm.dropPending("c1")
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            val before = vm.notebook.value
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher).onVersion("srv", "local", 1)
            assertThat(vm.notebook.value).isEqualTo(before)
        }

    @Test
    fun failed_item_clears_pending_ticks() =
        runTest {
            val vm = board()
            vm.markSent(setOf(0))
            assertThat(
                vm.notebook.value.pages[0]
                    .changedSinceSend,
            ).isFalse()
            vm.dropPending("c1")
            val page = vm.notebook.value.pages[0]
            assertThat(page.pendingSegments).isEmpty()
            // The marks stay for a re-send: the page counts as changed again.
            assertThat(page.changedSinceSend).isTrue()
            assertThat(vm.textLayers.bitmapFor(page)).isNull()
        }

    @Test
    fun version_of_another_conversation_is_not_placed() =
        runTest {
            storeVersion()
            val vm = board(bound = "B")
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            val before = vm.notebook.value
            // The versions were read for conversation A, but the board switched to B before the placement.
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher).onVersion("srv", "A", 1)
            assertThat(vm.notebook.value).isEqualTo(before)
        }

    private val milk =
        SourceMap.stripWithColours("buy fresh milk").let {
            TextRun("L1", 1, 1, 4, 53f, display = it.display, colourSpans = it.spans, sourceMap = it.map, sourceStrokeIds = emptyList())
        }

    private val strike =
        Stroke("s", Tool.BALLPOINT, 3f, (0..20).map { Point(60f + it * 15f, g.textBandTop(4) + 30f, 0.5f, it * 10L) })

    private val delete = EditOp("op-1", OpKind.DELETE, OpTarget("L1", 4, 14, "fresh milk"), strokeIds = listOf("s"))

    /** A board showing v1 ("buy fresh milk" on row 4) with a strike sent as op-1 in upload c1. */
    private fun TestScope.struckBoard(op: EditOp = delete): BoardViewModel {
        val store = NotebookStore(File(tmp.root, "struck.json"))
        val page = Page(listOf(strike), false, text = listOf(milk), pendingOps = listOf(PendingOp("c1", op)))
        store.save(Notebook(canvas, listOf(page), 0, appliedSpecVersion = 1))
        return BoardViewModel(store, this, StandardTestDispatcher(testScheduler), canvas)
    }

    @Test
    fun stale_base_keeps_marks_and_renders_latest() =
        runTest {
            val vm = struckBoard()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = FreezingInput() }
            val surface = RecordingSurface(FreezingInput()).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            // 409 stale_base: the outbox fails the item, the board drops what it sent, the conversations refresh.
            vm.dropFailedUpload("c1", "local")
            val lines = """{"line_id":"L1","source_line":1,"origin":{"kept":"L1"}},{"line_id":"L2","source_line":2,"origin":null}"""
            db.specDao().upsert(SpecEntity("srv", 2, "buy fresh milk\neggs", "2026-11-07T00:00:00Z", "[]", """{"lines":[$lines]}"""))
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher).onVersion("srv", "local", 2)
            val page = vm.notebook.value.pages[0]
            // The latest version is on the board ...
            assertThat(page.text.map { it.lineId }).containsExactly("L1", "L2")
            assertThat(vm.notebook.value.appliedSpecVersion).isEqualTo(2)
            assertThat(surface.swaps).hasSize(1)
            // ... and the strike is still there for the user to check and send again, its preview included.
            assertThat(page.pendingOps).isEmpty()
            assertThat(page.visibleStrokes.map { it.id }).containsExactly("s")
            assertThat(page.changedSinceSend).isTrue()
            assertThat(vm.overlayFor(vm.notebook.value)).hasSize(1)
        }

    @Test
    fun applied_delete_consumes_the_strike_and_clears_its_preview() =
        runTest {
            val vm = struckBoard()
            vm.setStrikeOverlay(0, vm.strikeRects(vm.notebook.value))
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            val layout =
                """{"lines":[{"line_id":"L1","source_line":1,"origin":{"kept":"L1","op":"op-1"}}],""" +
                    """"ops":[{"id":"op-1","status":"applied"}]}"""
            db.specDao().upsert(SpecEntity("srv", 2, "buy", "2026-11-07T00:00:00Z", "[]", layout))
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher).onVersion("srv", "local", 2)
            val page = vm.notebook.value.pages[0]
            assertThat(page.text.single().display).isEqualTo("buy")
            assertThat(page.consumed).containsExactly("s")
            assertThat(vm.overlayFor(vm.notebook.value)).isEmpty()
        }

    @Test
    fun rejected_op_keeps_ink_and_its_note_goes_to_the_feedback_strip() =
        runTest {
            val vm = struckBoard()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            db.feedbackDao().upsert(FeedbackEntity("srv", 1, "note", "older", null, 1, "2026-11-07T09:00:00Z"))
            val layout =
                """{"lines":[{"line_id":"L1","source_line":1,"origin":{"kept":"L1"}}],""" +
                    """"ops":[{"id":"op-1","status":"rejected","note":"the words differ"}]}"""
            db.specDao().upsert(SpecEntity("srv", 2, "buy fresh milk", "2026-11-07T00:00:00Z", "[]", layout))
            NotebookSync(
                db.specDao(),
                vm,
                holds,
                main = dispatcher,
                worker = dispatcher,
                feedback = db.feedbackDao(),
                now = { "2026-11-07T10:00:00Z" },
            ).onVersion("srv", "local", 2)
            val page = vm.notebook.value.pages[0]
            assertThat(page.pendingOps).isEmpty()
            assertThat(page.visibleStrokes.map { it.id }).containsExactly("s")
            val latest =
                db
                    .feedbackDao()
                    .observe("srv")
                    .first()
                    .first()
            assertThat(latest.id).isLessThan(0)
            assertThat(latest.text).isEqualTo("Edit not applied (delete \"fresh milk\"): the words differ")
            assertThat(latest.whereText).isEqualTo("page 1")
            assertThat(latest.specVersion).isEqualTo(2)
            // The connector cursor ignores the local note.
            assertThat(db.feedbackDao().maxId("srv")).isEqualTo(1)
        }

    @Test
    fun rejected_note_op_shows_the_translators_reason() =
        runTest {
            val vm = struckBoard(EditOp("op-1", OpKind.NOTE, strokeIds = listOf("s"), rect = listOf(0f, 0f, 10f, 10f)))
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            val layout =
                """{"lines":[{"line_id":"L1","source_line":1,"origin":{"kept":"L1"}}],""" +
                    """"ops":[{"id":"op-1","status":"rejected","note":"unreadable"}]}"""
            db.specDao().upsert(SpecEntity("srv", 2, "buy fresh milk", "2026-11-07T00:00:00Z", "[]", layout))
            NotebookSync(
                db.specDao(),
                vm,
                holds,
                main = dispatcher,
                worker = dispatcher,
                feedback = db.feedbackDao(),
                now = { "2026-11-07T10:00:00Z" },
            ).onVersion("srv", "local", 2)
            val latest =
                db
                    .feedbackDao()
                    .observe("srv")
                    .first()
                    .first()
            assertThat(latest.text).isEqualTo("Note not applied: unreadable")
        }

    @Test
    fun local_notes_sort_by_time_across_timestamp_formats() =
        runTest {
            val dao = db.feedbackDao()
            // Server style with an offset and fractions, 10:00:00.5; a local note in Instant style at 10:00:00 is older.
            dao.upsert(FeedbackEntity("srv", 1, "note", "server", null, 1, "2026-11-07T10:00:00.500000+00:00"))
            dao.upsert(FeedbackEntity("srv", -1, "note", "local", null, 2, "2026-11-07T10:00:00Z"))
            dao.upsert(FeedbackEntity("srv", 2, "note", "newest", null, 1, "2026-11-07T10:00:01+00:00"))
            assertThat(dao.observe("srv").first().map { it.text }).containsExactly("newest", "server", "local").inOrder()
        }

    @Test
    fun a_version_applied_meanwhile_writes_no_notes() =
        runTest {
            val vm = struckBoard()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            val layout =
                """{"lines":[{"line_id":"L1","source_line":1,"origin":{"kept":"L1"}}],""" +
                    """"ops":[{"id":"op-1","status":"rejected","note":"the words differ"}]}"""
            db.specDao().upsert(SpecEntity("srv", 2, "buy fresh milk", "2026-11-07T00:00:00Z", "[]", layout))
            // Another placement of the same version lands while this one renders: this one is stale and notes nothing.
            val worker =
                object : CoroutineDispatcher() {
                    private var first = true

                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        if (first) {
                            first = false
                            vm.applyVersion(2, "buy fresh milk", NotebookSync.layoutModel(layout), vm.textLayers.layer.measure)
                        }
                        dispatcher.dispatch(context, block)
                    }
                }
            val sync = NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = worker, feedback = db.feedbackDao())
            val job = launch { sync.onVersion("srv", "local", 2) }
            advanceUntilIdle()
            job.join()
            assertThat(db.feedbackDao().all("srv")).isEmpty()
        }

    @Test
    fun newer_version_without_layout_releases_the_pending_ink() =
        runTest {
            val vm = struckBoard()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            sendFrom("c1", "sk1")
            db.specDao().upsert(SpecEntity("srv", 2, "typed note", "2026-11-07T00:00:00Z", """["sk1"]""", null))
            val sync = NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher, threads = db.threadDao())
            sync.onVersion("srv", "local", 2)
            val page = vm.notebook.value.pages[0]
            assertThat(page.pendingOps).isEmpty()
            assertThat(page.visibleStrokes.map { it.id }).containsExactly("s")
            assertThat(page.changedSinceSend).isTrue()
            assertThat(page.text).isEqualTo(listOf(milk))
            // The version without a layout is not a placed one.
            assertThat(vm.notebook.value.appliedSpecVersion).isEqualTo(1)
            assertThat(vm.overlayFor(vm.notebook.value)).hasSize(1)
        }

    /** The upload [clientId] was acknowledged by the connector as sketch [sketchId]. */
    private suspend fun sendFrom(
        clientId: String,
        sketchId: String,
    ) {
        db.conversationDao().upsert(ConversationEntity("local", "srv", "t", 0, 0))
        db.threadDao().upsert(ThreadItemEntity("i-$clientId", "local", ThreadKind.SKETCH, clientId, null, 1, null, "sent", 1, sketchId))
    }

    @Test
    fun a_version_releases_only_the_uploads_it_answers_when_two_share_a_base() =
        runTest {
            val store = NotebookStore(File(tmp.root, "two.json"))
            val pending =
                listOf(
                    PendingSegment("c1", "p0-r4-0", 4, 100f, 300f, listOf("a", "b"), 1),
                    PendingSegment("c2", "p0-r6-0", 6, 100f, 300f, listOf("c", "d"), 1),
                )
            val page = Page(listOf(stroke("a", 4), stroke("b", 4), stroke("c", 6), stroke("d", 6)), false, pendingSegments = pending)
            store.save(Notebook(canvas, listOf(page), 0, appliedSpecVersion = 1))
            val vm = BoardViewModel(store, this, StandardTestDispatcher(testScheduler), canvas)
            sendFrom("c1", "sk1")
            sendFrom("c2", "sk2")
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            // Version 2 answers the first upload only; the second is still in flight on the same base.
            db.specDao().upsert(SpecEntity("srv", 2, "other", "2026-11-07T00:00:00Z", """["sk1"]""", """{"lines":[],"ops":[]}"""))
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher, threads = db.threadDao())
                .onVersion("srv", "local", 2)
            val result = vm.notebook.value.pages[0]
            assertThat(result.pendingSegments.map { it.clientId }).containsExactly("c2")
            assertThat(result.changedSinceSend).isTrue()
        }

    @Test
    fun newer_layout_version_not_listing_the_pending_releases_it() =
        runTest {
            val vm = board()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            val other = """{"lines":[{"line_id":"L9","source_line":1,"origin":null}],"ops":[]}"""
            sendFrom("c1", "sk1")
            db.specDao().upsert(SpecEntity("srv", 1, "other", "2026-11-07T00:00:00Z", """["sk1"]""", other))
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher, threads = db.threadDao())
                .onVersion("srv", "local", 1)
            val page = vm.notebook.value.pages[0]
            assertThat(page.pendingSegments).isEmpty()
            assertThat(page.consumed).isEmpty()
            assertThat(page.visibleStrokes.map { it.id }).containsExactly("a", "b")
            assertThat(page.changedSinceSend).isTrue()
        }

    @Test
    fun release_pending_clears_everything_on_the_board() =
        runTest {
            val vm = struckBoard()
            advanceTimeBy(1_000)
            vm.releasePending(null)
            val page = vm.notebook.value.pages[0]
            assertThat(page.pendingOps).isEmpty()
            assertThat(page.visibleStrokes.map { it.id }).containsExactly("s")
            assertThat(page.changedSinceSend).isTrue()
        }

    @Test
    fun undoing_the_stroke_of_a_pending_op_keeps_the_op_pending_and_the_later_version_applies() =
        runTest {
            val store = NotebookStore(File(tmp.root, "undo.json"))
            store.save(Notebook(canvas, listOf(Page(emptyList(), false, text = listOf(milk))), 0, appliedSpecVersion = 1))
            val vm = BoardViewModel(store, this, StandardTestDispatcher(testScheduler), canvas)
            vm.onStroke(strike)
            vm.addPending(emptyMap(), mapOf(0 to listOf(PendingOp("c1", delete, 1))))
            vm.undo()
            assertThat(
                vm.notebook.value.pages[0]
                    .strokes,
            ).isEmpty()
            // The server still applies the op: the later version consumes a stroke that is gone, harmlessly.
            assertThat(
                vm.notebook.value.pages[0]
                    .pendingOps,
            ).hasSize(1)
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            val layout =
                """{"lines":[{"line_id":"L1","source_line":1,"origin":{"kept":"L1","op":"op-1"}}],""" +
                    """"ops":[{"id":"op-1","status":"applied"}]}"""
            db.specDao().upsert(SpecEntity("srv", 2, "buy", "2026-11-07T00:00:00Z", "[]", layout))
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher).onVersion("srv", "local", 2)
            val page = vm.notebook.value.pages[0]
            assertThat(page.pendingOps).isEmpty()
            assertThat(page.text.single().display).isEqualTo("buy")
            assertThat(page.visibleStrokes).isEmpty()
        }
}
