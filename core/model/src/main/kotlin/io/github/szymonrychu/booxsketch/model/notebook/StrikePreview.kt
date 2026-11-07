package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.RectF

/**
 * The optimistic strike preview (spec 3.5): the text band rect of every word a visible stroke strikes through, drawn
 * in grey over the text until the version that deletes them arrives. Local only and never stored: recomputed from the
 * page state, so undo, erase, a rejected op's version or a stale base re-render all agree with what is on the page.
 */
object StrikePreview {
    fun rects(
        page: Page,
        geometry: NotebookGeometry,
        measure: TextMeasure,
    ): List<RectF> {
        if (page.text.isEmpty()) return emptyList()
        val boxes = page.text.associateWith { WordBoxes.of(it, geometry, measure) }
        return page.visibleStrokes
            .filter { it.points.isNotEmpty() }
            .mapNotNull { struckWords(it, boxes, geometry) }
            .flatMap { (run, range) ->
                boxes
                    .getValue(run)
                    .filter { it.from >= range.first && it.to <= range.last + 1 }
                    .groupBy { it.row }
                    .map { (row, words) ->
                        RectF(
                            words.minOf { it.left },
                            geometry.textBandTop(row).toFloat(),
                            words.maxOf { it.right },
                            geometry.rowLineY(row).toFloat(),
                        )
                    }
            }.distinct()
    }
}
