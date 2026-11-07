package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The optimistic strike preview (Task 5.3): grey rects over struck words, swapped in at pen idle only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StrikePreviewTest {
    @get:Rule val tmp = TemporaryFolder()

    private val g = NotebookGeometry(1404, 1872)
    private val canvas = Canvas(1404, 1872, 300f)

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

    private val milk =
        SourceMap.stripWithColours("buy fresh milk").let {
            TextRun("L1", 1, 1, 4, 53f, display = it.display, colourSpans = it.spans, sourceMap = it.map, sourceStrokeIds = emptyList())
        }

    /** A flat stroke through the middle of row 4's text band, across the whole line. */
    private val strike =
        Stroke("s", Tool.BALLPOINT, 3f, (0..20).map { Point(60f + it * 15f, g.textBandTop(4) + 30f, 0.5f, it * 10L) })

    private fun TestScope.board(): BoardViewModel {
        val store = NotebookStore(File(tmp.root, "n.json"))
        store.save(Notebook(canvas, listOf(Page(emptyList(), false, text = listOf(milk))), 0))
        return BoardViewModel(store, this, StandardTestDispatcher(testScheduler), canvas)
    }

    @Test
    fun strike_overlay_drawn_at_idle_not_on_pen_up() =
        runTest {
            val vm = board()
            val input = FreezingInput()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = input }
            val surface = RecordingSurface(input).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sync = StrikePreviewSync(vm, holds, main = dispatcher, worker = dispatcher)
            advanceTimeBy(1_000)
            // Pen-up of the strike: the stroke is committed, the pen was active just now.
            holds.onPenActivity()
            vm.onStroke(strike)
            val job = launch { sync.refresh() }
            advanceTimeBy(TextLayerHolds.PEN_IDLE_MS - 10)
            runCurrent()
            assertThat(surface.swaps).isEmpty()
            assertThat(input.log).doesNotContain("hold")
            assertThat(vm.overlayFor(vm.notebook.value)).isEmpty()
            advanceUntilIdle()
            job.join()
            // At pen idle: one swap under one hold, with the overlay over the text band of row 4.
            assertThat(surface.swaps).hasSize(1)
            assertThat(surface.swaps.single().second).isTrue()
            assertThat(input.held).isEqualTo(0)
            val overlay = vm.overlayFor(vm.notebook.value)
            assertThat(overlay).hasSize(1)
            assertThat(overlay.single().t).isEqualTo(g.textBandTop(4).toFloat())
            assertThat(overlay.single().b).isEqualTo(g.rowLineY(4).toFloat())
            assertThat(surface.dirty!!.top).isEqualTo(g.textBandTop(4).toFloat())
            // The board's next frame finds the swapped bitmap.
            val page = vm.notebook.value.pages[0]
            assertThat(vm.textLayers.bitmapFor(page, overlay)).isSameInstanceAs(surface.swaps.single().first)
            // Nothing changed: no second swap.
            sync.refresh()
            assertThat(surface.swaps).hasSize(1)
        }

    @Test
    fun undo_of_the_strike_clears_the_overlay_at_idle() =
        runTest {
            val vm = board()
            val input = FreezingInput()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = input }
            val surface = RecordingSurface(input).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sync = StrikePreviewSync(vm, holds, main = dispatcher, worker = dispatcher)
            advanceTimeBy(1_000)
            vm.onStroke(strike)
            sync.refresh()
            assertThat(vm.overlayFor(vm.notebook.value)).hasSize(1)
            vm.undo()
            sync.refresh()
            assertThat(vm.overlayFor(vm.notebook.value)).isEmpty()
            assertThat(surface.swaps).hasSize(2)
        }

    @Test
    fun writing_that_is_no_strike_never_swaps() =
        runTest {
            val vm = board()
            val holds = TextLayerHolds(clock = { testScheduler.currentTime }).also { it.input = FreezingInput() }
            val surface = RecordingSurface(FreezingInput()).also { holds.surface = it }
            val dispatcher = StandardTestDispatcher(testScheduler)
            advanceTimeBy(1_000)
            vm.onStroke(Stroke("w", Tool.BALLPOINT, 3f, listOf(Point(100f, 900f, 0.5f, 0L), Point(300f, 900f, 0.5f, 10L))))
            StrikePreviewSync(vm, holds, main = dispatcher, worker = dispatcher).refresh()
            assertThat(surface.swaps).isEmpty()
        }
}
