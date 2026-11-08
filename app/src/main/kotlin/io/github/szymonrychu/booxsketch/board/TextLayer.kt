package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.notebook.TextMeasure
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import io.github.szymonrychu.booxsketch.model.notebook.WordBoxes
import io.github.szymonrychu.booxsketch.model.rectOf
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.model.RectF as ModelRect

/**
 * The page-sized text bitmap of a notebook page (spec 6, Q5 A): every [TextRun] in sans serif, 40 px at scale 1,
 * black with its colour spans in their pen colour, headings bold, the baseline 11 px above the row line; a grey tick
 * at the start of each row with pending segments (spec 3.3.6); [render]'s overlay rects in 50 percent grey (the P5
 * strike preview); a thin dashed dark grey frame with a small "image" label for every applied image region (spec
 * 3.6). The background is transparent: the board draws it over the grid and under the ink, the export over white.
 * [measure] uses the same paint settings, so the placement fits what is drawn.
 */
class TextLayer(
    private val geometry: NotebookGeometry,
) {
    private val measurePaints = mapOf(false to textPaint(bold = false), true to textPaint(bold = true))

    val measure: TextMeasure =
        object : TextMeasure {
            override fun width(
                text: String,
                scale: Float,
            ): Float = width(text, scale, bold = false)

            override fun width(
                text: String,
                scale: Float,
                bold: Boolean,
            ): Float {
                val paint = measurePaints.getValue(bold)
                return synchronized(paint) {
                    paint.textSize = TEXT_SIZE * scale
                    paint.measureText(text)
                }
            }
        }

    /** The layer an export or a region crop composites: the text only, no pending ticks and no applied region frames. */
    fun renderForExport(page: Page): Bitmap = render(page.copy(pendingSegments = emptyList(), appliedRegions = emptyMap()), null)

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
        appliedRegionRects(page).forEach { drawRegionFrame(canvas, it) }
        return bitmap
    }

    private fun drawRegionFrame(
        canvas: Canvas,
        r: ModelRect,
    ) {
        val frame =
            Paint().apply {
                isAntiAlias = true
                color = REGION_GREY
                style = Paint.Style.STROKE
                strokeWidth = REGION_FRAME
                pathEffect = DashPathEffect(floatArrayOf(CanvasStrokeRenderer.DASH, CanvasStrokeRenderer.DASH), 0f)
            }
        canvas.drawRect(r.l, r.t, r.r, r.b, frame)
        val label =
            Paint().apply {
                isAntiAlias = true
                color = REGION_GREY
                textSize = REGION_LABEL_SIZE
                typeface = Typeface.SANS_SERIF
            }
        canvas.drawText(REGION_LABEL, r.l + REGION_LABEL_INSET, r.t + REGION_LABEL_INSET + REGION_LABEL_SIZE, label)
    }

    private fun drawRun(
        canvas: Canvas,
        run: TextRun,
        paint: Paint,
    ) {
        paint.textSize = TEXT_SIZE * run.scale
        val rows = WordBoxes.rows(run, geometry, measure)
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

        /** An applied image region: a [REGION_FRAME] px dashed frame and a [REGION_LABEL_SIZE] px label, dark grey. */
        const val REGION_GREY = 0xFF555555.toInt()
        const val REGION_FRAME = 2f
        const val REGION_LABEL = "image"
        const val REGION_LABEL_SIZE = 20f
        const val REGION_LABEL_INSET = 6f
    }
}

/** Rects of the applied image regions of [page] (their strokes may have moved with a row shift). */
internal fun appliedRegionRects(page: Page): List<ModelRect> =
    if (page.appliedRegions.isEmpty()) {
        emptyList()
    } else {
        page.strokes.filter { it.id in page.appliedRegions && it.points.size >= 2 }.map(::rectOf)
    }

/**
 * The text bitmap of the selected page only (spec 6: one page-sized bitmap is 10.5 MB). [bitmapFor] returns the
 * cached bitmap while the page's text, pending rows, applied region frames and strike overlay are unchanged, else
 * renders a new one; a page with none of them has none. A change always yields a new instance (never drawn into the
 * shown one), because the board redraws on a new instance and must never show a half-drawn layer. [prerender] does the
 * same off the main thread ahead of a swap.
 */
class TextLayerCache(
    val layer: TextLayer,
) {
    private var key: Any? = null
    private var bitmap: Bitmap? = null

    /** [overlay]: the strike preview rects (page px) drawn in grey over the text. */
    @Synchronized
    fun bitmapFor(
        page: Page,
        overlay: List<ModelRect> = emptyList(),
    ): Bitmap? {
        val k = keyOf(page, overlay) ?: return null
        if (k == key) return bitmap
        val b = layer.render(page, null, overlay.map { RectF(it.l, it.t, it.r, it.b) })
        key = k
        bitmap = b
        return b
    }

    /** Renders [page]'s layer now (any thread) so a later [bitmapFor] of the same page returns it at once. */
    fun prerender(
        page: Page,
        overlay: List<ModelRect> = emptyList(),
    ): Bitmap? = bitmapFor(page, overlay)

    private fun keyOf(
        page: Page,
        overlay: List<ModelRect>,
    ): Any? {
        val regions = appliedRegionRects(page)
        if (page.text.isEmpty() && page.pendingSegments.isEmpty() && regions.isEmpty()) return null
        return listOf(page.text, page.pendingSegments.map { it.row }.toSet(), regions, overlay)
    }
}
