package io.github.szymonrychu.booxsketch.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun TestScope.board(): BoardViewModel {
        val store = NotebookStore(File(tmp.root, "n.json"))
        val pending = listOf(PendingSegment("c1", "p0-r4-0", 4, 100f, 300f, listOf("a", "b")))
        store.save(Notebook(canvas, listOf(Page(listOf(stroke("a", 4), stroke("b", 4)), false, pendingSegments = pending)), 0))
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
            sync.onVersion("srv", 1)
            val page = vm.notebook.value.pages[0]
            assertThat(page.text.single().display).isEqualTo("buy milk")
            assertThat(page.consumed).containsExactly("a", "b")
            assertThat(page.pendingSegments).isEmpty()
            assertThat(vm.notebook.value.appliedSpecVersion).isEqualTo(1)
            assertThat(surface.swaps).hasSize(1)
            assertThat(surface.swaps.single().first).isNotNull()
            assertThat(surface.dirty!!.top).isEqualTo(g.rowTop(4).toFloat())
            // The cached layer is the swapped one: the next board frame finds it and does not redraw.
            assertThat(vm.textLayers.bitmapFor(page)).isSameInstanceAs(surface.swaps.single().first)
            // A version already applied is not applied again.
            sync.onVersion("srv", 1)
            assertThat(surface.swaps).hasSize(1)
            assertThat(input.held).isEqualTo(0)
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
            val job = launch { sync.onVersion("srv", 1) }
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
            val job = launch { sync.onVersion("srv", 1) }
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
            val holds = TextLayerHolds(clock = { testScheduler.currentTime })
            val dispatcher = StandardTestDispatcher(testScheduler)
            val before = vm.notebook.value
            NotebookSync(db.specDao(), vm, holds, main = dispatcher, worker = dispatcher).onVersion("srv", 1)
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
}
