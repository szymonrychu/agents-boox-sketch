package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.notebook.RunPlacer
import io.github.szymonrychu.booxsketch.model.notebook.TextMeasure
import io.github.szymonrychu.booxsketch.model.notebook.TextRun

/**
 * The page-sized text bitmap of a notebook page (spec 6, Q5 A): every [TextRun] in sans serif, 40 px at scale 1,
 * black with its colour spans in their pen colour, headings bold, the baseline 11 px above the row line; a grey tick
 * at the start of each row with pending segments (spec 3.3.6); [render]'s overlay rects in 50 percent grey (the P5
 * strike preview). The background is transparent: the board draws it over the grid and under the ink, the export
 * over white. [measure] uses the same paint settings, so the placement fits what is drawn.
 */
class TextLayer(
    private val geometry: NotebookGeometry,
) {
    private val measurePaint = textPaint(bold = false)

    val measure: TextMeasure =
        TextMeasure { text, scale ->
            synchronized(measurePaint) {
                measurePaint.textSize = TEXT_SIZE * scale
                measurePaint.measureText(text)
            }
        }

    /** Draws [page]'s text into [reuse] (cleared) when it has the page size, else into a new bitmap. */
    fun render(
        page: Page,
        reuse: Bitmap?,
        overlay: List<RectF> = emptyList(),
    ): Bitmap {
        val bitmap =
            reuse
                ?.takeIf { it.width == geometry.pageW && it.height == geometry.pageH && it.isMutable && !it.isRecycled }
                ?.apply { eraseColor(Color.TRANSPARENT) }
                ?: Bitmap.createBitmap(geometry.pageW, geometry.pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val regular = textPaint(bold = false)
        val bold = textPaint(bold = true)
        for (run in page.text) drawRun(canvas, run, if (run.heading) bold else regular)
        val grey = Paint().apply { color = OVERLAY_GREY }
        overlay.forEach { canvas.drawRect(it, grey) }
        val tick = tickPaint()
        page.pendingSegments
            .map { it.row }
            .distinct()
            .forEach { drawTick(canvas, it, tick) }
        return bitmap
    }

    private fun drawRun(
        canvas: Canvas,
        run: TextRun,
        paint: Paint,
    ) {
        paint.textSize = TEXT_SIZE * run.scale
        val rows =
            if (run.wrapRows <= 1) {
                listOf(run.display.indices)
            } else {
                val parts = RunPlacer.wrap(run.display, run.scale, geometry.marginX - run.x0, measure)
                // The placement may have wrapped in a narrower column (a second run on the row): the last row takes the rest.
                if (parts.size <=
                    run.wrapRows
                ) {
                    parts
                } else {
                    parts.take(run.wrapRows - 1) + listOf(parts[run.wrapRows - 1].first until run.display.length)
                }
            }
        rows.forEachIndexed { k, range ->
            val baseline = (geometry.rowLineY(run.row + k) - BASELINE_ABOVE_LINE).toFloat()
            drawSpans(canvas, run, range, run.x0, baseline, paint)
        }
    }

    /** Draws display chars [range] of [run] from [x0], switching colour at span edges. */
    private fun drawSpans(
        canvas: Canvas,
        run: TextRun,
        range: IntRange,
        x0: Float,
        baseline: Float,
        paint: Paint,
    ) {
        if (range.isEmpty()) return
        val cuts =
            (listOf(range.first, range.last + 1) + run.colourSpans.flatMap { listOf(it.from, it.to) })
                .filter { it in range.first..range.last + 1 }
                .distinct()
                .sorted()
        var x = x0
        for ((from, to) in cuts.zipWithNext()) {
            paint.color = run.colourSpans
                .firstOrNull { from >= it.from && from < it.to }
                ?.color
                ?.argb ?: Color.BLACK
            canvas.drawText(run.display, from, to, x, baseline, paint)
            x += paint.measureText(run.display, from, to)
        }
    }

    private fun drawTick(
        canvas: Canvas,
        row: Int,
        paint: Paint,
    ) {
        val x = (geometry.textLeft - TICK_INSET).toFloat()
        val y = (geometry.rowLineY(row) - BASELINE_ABOVE_LINE).toFloat()
        canvas.drawLine(x, y - TICK / 2f, x + TICK / 3f, y, paint)
        canvas.drawLine(x + TICK / 3f, y, x + TICK, y - TICK, paint)
    }

    private fun textPaint(bold: Boolean) =
        Paint().apply {
            isAntiAlias = true
            hinting = Paint.HINTING_OFF
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }

    private fun tickPaint() =
        Paint().apply {
            isAntiAlias = true
            color = TICK_GREY
            strokeWidth = TICK_WIDTH
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    companion object {
        /** Text size at scale 1, page px (Q5 A: half a row). */
        const val TEXT_SIZE = 40f

        /** The baseline sits this far above the row line, so descenders stay in the text band. */
        const val BASELINE_ABOVE_LINE = 11

        /** The pending tick: [TICK] px wide, [TICK_INSET] px left of the text column. */
        const val TICK = 12f
        const val TICK_INSET = 30
        const val TICK_WIDTH = 3f
        const val TICK_GREY = 0xFF808080.toInt()

        /** 50 percent grey: the strike preview over text. */
        const val OVERLAY_GREY = 0x80808080.toInt()
    }
}

/**
 * The text bitmap of the selected page only (spec 6: one page-sized bitmap is 10.5 MB). [bitmapFor] returns the
 * cached bitmap while the page's text and pending rows are unchanged, else renders a new one; a page with neither has
 * none. A change always yields a new instance (never drawn into the shown one), because the board redraws on a new
 * instance and must never show a half-drawn layer. [prerender] does the same off the main thread ahead of a swap.
 */
class TextLayerCache(
    val layer: TextLayer,
) {
    private var key: Any? = null
    private var bitmap: Bitmap? = null

    @Synchronized
    fun bitmapFor(page: Page): Bitmap? {
        val k = keyOf(page) ?: return null
        if (k == key) return bitmap
        val b = layer.render(page, null)
        key = k
        bitmap = b
        return b
    }

    /** Renders [page]'s layer now (any thread) so a later [bitmapFor] of the same page returns it at once. */
    fun prerender(page: Page): Bitmap? = bitmapFor(page)

    private fun keyOf(page: Page): Any? =
        if (page.text.isEmpty() && page.pendingSegments.isEmpty()) null else page.text to page.pendingSegments.map { it.row }.toSet()
}
