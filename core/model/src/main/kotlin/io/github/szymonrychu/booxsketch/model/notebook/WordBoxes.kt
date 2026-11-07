package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry

/** The glyph box of one word of a [TextRun]: display offsets `[from, to)`, page x [left] to [right], on page row [row]. */
data class WordBox(
    val from: Int,
    val to: Int,
    val left: Float,
    val right: Float,
    val row: Int,
) {
    val centre: Float get() = (left + right) / 2f
}

/**
 * Word boxes of rendered runs (spec 3.4, 3.5, 3.7): where each word of a run is drawn, measured with the same
 * [TextMeasure] the text layer draws with, on every row a wrapped run takes. Annotations target whole words through
 * them.
 */
object WordBoxes {
    /** Display offset ranges of the rows [run] is drawn on, in order: the placement's wrap, the rest on the last row. */
    fun rows(
        run: TextRun,
        geometry: NotebookGeometry,
        measure: TextMeasure,
    ): List<IntRange> {
        if (run.wrapRows <= 1) return listOf(run.display.indices)
        // The column the placement wrapped in (a neighbour on the row may have narrowed it), so the rows match.
        val column = if (run.column > 0f) run.column else geometry.marginX - run.x0
        val parts = RunPlacer.wrap(run.display, run.scale, column, measure)
        if (parts.size <= run.wrapRows) return parts
        return parts.take(run.wrapRows - 1) + listOf(parts[run.wrapRows - 1].first until run.display.length)
    }

    fun of(
        run: TextRun,
        geometry: NotebookGeometry,
        measure: TextMeasure,
    ): List<WordBox> =
        rows(run, geometry, measure).withIndex().flatMap { (k, range) ->
            words(run.display, range).map { (from, to) ->
                fun x(offset: Int) = run.x0 + measure.width(run.display.substring(range.first, offset), run.scale)
                WordBox(from, to, x(from), x(to), run.row + k)
            }
        }

    /** Display offsets `[from, to)` of the whole words whose boxes overlap page x [x0]..[x1]; null when none does. */
    fun overlapped(
        boxes: List<WordBox>,
        x0: Float,
        x1: Float,
    ): IntRange? {
        val hit = boxes.filter { it.right > x0 && it.left < x1 }
        if (hit.isEmpty()) return null
        return hit.minOf { it.from } until hit.maxOf { it.to }
    }

    /**
     * The word boundary nearest page x [x] (display offset): the start of the first word left of its centre, else the
     * end of the last word whose centre lies at or left of [x].
     */
    fun nearestBoundary(
        boxes: List<WordBox>,
        x: Float,
    ): Int {
        val sorted = boxes.sortedBy { it.left }
        val before = sorted.lastOrNull { it.centre <= x } ?: return sorted.firstOrNull()?.from ?: 0
        return before.to
    }

    /** `[from, to)` of each run of non-space chars of [text] within [range]. */
    private fun words(
        text: String,
        range: IntRange,
    ): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = range.first
        val end = range.last + 1
        while (i < end) {
            while (i < end && text[i] == ' ') i++
            if (i >= end) break
            val from = i
            while (i < end && text[i] != ' ') i++
            out += from to i
        }
        return out
    }
}
