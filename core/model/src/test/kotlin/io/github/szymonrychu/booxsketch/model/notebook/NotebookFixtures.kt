package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool

/** Notebook pages for the annotation tests: the board geometry, a 20 px per char measure, runs and strokes. */
object NotebookFixtures {
    val g = NotebookGeometry(1404, 1872)

    /** 20 px per char at scale 1. */
    val measure = TextMeasure { text, scale -> text.length * 20f * scale }

    /** A run of markdown [source] on [row] from [x0]. */
    fun run(
        lineId: String,
        row: Int,
        source: String,
        x0: Float = 53f,
        sourceLine: Int = 1,
    ): TextRun {
        val s = SourceMap.stripWithColours(source)
        return TextRun(
            lineId,
            1,
            sourceLine,
            row,
            x0,
            display = s.display,
            colourSpans = s.spans,
            sourceMap = s.map,
            sourceStrokeIds = emptyList(),
            source = source,
        )
    }

    /** A pen stroke through [xy] (x, y pairs), points 10 ms apart. */
    fun pen(
        id: String,
        vararg xy: Float,
    ) = Stroke(id, Tool.BALLPOINT, 2f, xy.toList().chunked(2).mapIndexed { i, (x, y) -> Point(x, y, 0.5f, i * 10L) })

    /** A straight pen stroke from x [x0] to [x1] at y [y], sampled every 10 px. */
    fun line(
        id: String,
        x0: Float,
        x1: Float,
        y: Float,
    ): Stroke {
        val n = ((x1 - x0) / 10f).toInt().coerceAtLeast(1)
        return pen(id, *(0..n).flatMap { listOf(x0 + (x1 - x0) * it / n, y) }.toFloatArray())
    }

    /** Handwriting-like ink from [x0] to [x1] centred at [y]: a small zig-zag, 16 px tall. */
    fun word(
        id: String,
        x0: Float,
        x1: Float,
        y: Float,
    ): Stroke {
        val n = ((x1 - x0) / 8f).toInt().coerceAtLeast(2)
        return pen(id, *(0..n).flatMap { listOf(x0 + (x1 - x0) * it / n, if (it % 2 == 0) y - 8f else y + 8f) }.toFloatArray())
    }

    fun rect(
        id: String,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) = Stroke(id, Tool.RECTANGLE, 4f, listOf(Point(l, t, 1f, 0L), Point(r, b, 1f, 10L)))

    fun arrow(
        id: String,
        tailX: Float,
        tailY: Float,
        tipX: Float,
        tipY: Float,
    ) = Stroke(id, Tool.ARROW, 4f, listOf(Point(tailX, tailY, 1f, 0L), Point(tipX, tipY, 1f, 10L)))

    /** Y in the annotation band (upper half) of [row]. */
    fun annotationY(row: Int) = g.rowTop(row) + 26f

    /** Y in the text band (lower half) of [row], at the middle of the glyphs. */
    fun textY(row: Int) = g.textBandTop(row) + 30f

    fun page(
        runs: List<TextRun>,
        vararg strokes: Stroke,
    ) = Page(strokes.toList(), true, text = runs)
}
