package io.github.szymonrychu.booxsketch.board

import android.graphics.RectF
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import io.github.szymonrychu.booxsketch.model.RectF as ModelRect

/**
 * The optimistic strike preview on the board (spec 3.5, Task 5.3). After every board change it waits for pen idle
 * ([TextLayerHolds.awaitPenIdle]: never on pen-up, raw drawing must not see a redraw while the pen writes), recomputes
 * the grey rects over struck words from the selected page ([BoardViewModel.strikeRects]), renders the text layer with
 * them on [worker] and, when they changed, swaps it in under one hold: the overlay state and the bitmap change in the
 * same main-thread step, so the board's next frame finds that bitmap cached. A board that changed meanwhile, or a pen
 * that became busy, starts over.
 */
class StrikePreviewSync(
    private val board: BoardViewModel,
    private val holds: TextLayerHolds,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Refreshes the preview after each board change until cancelled. */
    suspend fun run(): Nothing {
        board.notebook.collectLatest { refresh() }
    }

    suspend fun refresh() {
        while (true) {
            holds.awaitPenIdle()
            val (n, rects, shown) =
                withContext(
                    main,
                ) { board.notebook.value.let { Triple(it, board.strikeRects(it), board.overlayFor(it)) } }
            if (rects == shown) return
            val bitmap = withContext(worker) { board.textLayers.prerender(n.pages[n.current], rects) }
            val done =
                withContext(main) {
                    if (board.notebook.value !== n || !holds.idleNow()) return@withContext false
                    holds.hold {
                        board.setStrikeOverlay(n.current, rects)
                        holds.surface?.swapText(shownStrokes(n), bitmap, rows(NotebookGeometry(n.canvas.w, n.canvas.h), shown + rects))
                        holds.ui?.uiChanged()
                    }
                    true
                }
            if (done) return
        }
    }

    /** The full-width band of rows the old and new rects cover (page px). */
    private fun rows(
        g: NotebookGeometry,
        rects: List<ModelRect>,
    ): RectF = RectF(0f, rects.minOf { it.t }, g.pageW.toFloat(), rects.maxOf { it.b })
}
