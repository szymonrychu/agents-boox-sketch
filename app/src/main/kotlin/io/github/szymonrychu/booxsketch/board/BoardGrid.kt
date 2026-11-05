package io.github.szymonrychu.booxsketch.board

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/**
 * The notebook page behind the ink: light grey row lines over the text column, one vertical line at
 * the right margin and sparse dots at the cell intersections ([NotebookGeometry]). Everything is
 * defined in page-canvas pixels and mapped through the [BoardGeometry] of the frame, like the ink, so
 * it stays locked to the strokes in portrait and landscape. It is a view-only background: the
 * exporters and the strokes codec never see it. Only what lies in the clip is drawn, through two
 * reused buffers, so a dirty-rect frame costs a handful of primitives and nothing is allocated per frame.
 */
class BoardGrid {
    private val linePaint =
        Paint().apply {
            color = LINE_COLOR
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }
    private val dotPaint =
        Paint().apply {
            color = DOT_COLOR
            strokeCap = Paint.Cap.SQUARE
            style = Paint.Style.STROKE
            isAntiAlias = false
        }
    private var geometry = NotebookGeometry(0, 0)
    private val area = RectF()
    private var lineBuf = FloatArray(0)
    private var dotBuf = FloatArray(0)

    /**
     * Draws the part of the grid of [page] that lies in the page and in [within] (view pixels; null: all of
     * the page), placed by [g].
     */
    fun draw(
        canvas: Canvas,
        g: BoardGeometry,
        page: PageCanvas,
        within: RectF?,
    ) {
        if (geometry.pageW != page.w || geometry.pageH != page.h) geometry = NotebookGeometry(page.w, page.h)
        area.set(g.dx, g.dy, g.dx + page.w * g.scale, g.dy + page.h * g.scale)
        if (within != null && !area.intersect(within)) return
        if (area.isEmpty || g.scale <= 0f) return
        // The visible part in page pixels.
        val left = (area.left - g.dx) / g.scale
        val right = (area.right - g.dx) / g.scale
        val top = (area.top - g.dy) / g.scale
        val bottom = (area.bottom - g.dy) / g.scale
        val lines = fillLines(g, left, right, top, bottom)
        val dots = fillDots(g, left, right, top, bottom)
        if (lines > 0) canvas.drawLines(lineBuf, 0, lines, linePaint)
        if (dots > 0) {
            dotPaint.strokeWidth = max(DOT_PX.toFloat(), (DOT_PX * g.scale).roundToInt().toFloat())
            canvas.drawPoints(dotBuf, 0, dots, dotPaint)
        }
    }

    /** Fills [lineBuf] with the row lines and the margin line in the visible page part; returns the float count. */
    private fun fillLines(
        g: BoardGeometry,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): Int {
        val rows = geometry.rowLinesIn(top, bottom)
        val spanLeft = max(area.left, g.dx + geometry.textLeft * g.scale)
        val spanRight = minOf(area.right, g.dx + geometry.marginX * g.scale)
        val rowCount = if (spanLeft < spanRight) (rows.last - rows.first + 1).coerceAtLeast(0) else 0
        val marginVisible = geometry.marginX.toFloat() in left..right
        val count = rowCount + if (marginVisible) 1 else 0
        if (lineBuf.size < count * 4) lineBuf = FloatArray(count * 4)
        var i = 0
        if (rowCount > 0) {
            for (k in rows) {
                val y = pixelCentre(g.dy + geometry.rowLineY(k) * g.scale)
                lineBuf[i++] = spanLeft
                lineBuf[i++] = y
                lineBuf[i++] = spanRight
                lineBuf[i++] = y
            }
        }
        if (marginVisible) {
            val x = pixelCentre(g.dx + geometry.marginX * g.scale)
            lineBuf[i++] = x
            lineBuf[i++] = area.top
            lineBuf[i++] = x
            lineBuf[i++] = area.bottom
        }
        return i
    }

    private fun fillDots(
        g: BoardGeometry,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): Int {
        val columns = geometry.dotColumnsIn(left, right)
        val rows = geometry.dotRowsIn(top, bottom)
        val capacity = (columns.last - columns.first + 1).coerceAtLeast(0) * (rows.last - rows.first + 1).coerceAtLeast(0) * 2
        if (dotBuf.size < capacity) dotBuf = FloatArray(capacity)
        var i = 0
        for (j in rows) {
            val y = g.dy + j * geometry.cell * g.scale
            for (c in columns) {
                if (geometry.dotCovered(c, j)) continue
                dotBuf[i++] = g.dx + c * geometry.cell * g.scale
                dotBuf[i++] = y
            }
        }
        return i
    }

    /** The middle of the view pixel row (or column) that [v] falls in, so a 1 px non-anti-aliased line covers exactly one. */
    private fun pixelCentre(v: Float): Float = floor(v) + 0.5f

    companion object {
        /** Light grey: reads lightly on e-ink but stays visible. */
        const val LINE_COLOR = 0xFFD3D3D3.toInt()

        /** A little darker than the lines: a dot is small. */
        const val DOT_COLOR = 0xFFBDBDBD.toInt()

        /** Dot side in page pixels. */
        const val DOT_PX = 2
    }
}
