package io.github.szymonrychu.booxsketch.board

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The light grey 5 mm square grid behind the ink, like a notebook page. [pitchX] and [pitchY] are
 * the grid pitch in view pixels (see [pitchPx]). It is a view-only background: the exporters and
 * the strokes codec never see it. Lines are drawn through one reused buffer, only those inside the
 * clip, so a dirty-rect frame costs a handful of lines and nothing is allocated per frame.
 */
class BoardGrid(
    private val pitchX: Float,
    private val pitchY: Float,
) {
    private val paint =
        Paint().apply {
            color = COLOR
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }
    private val area = RectF()
    private var buf = FloatArray(0)

    /** Draws the lines of the grid anchored at the corner of [page] that lie in [page] and [within] (null: all of [page]). */
    fun draw(
        canvas: Canvas,
        page: RectF,
        within: RectF?,
    ) {
        area.set(page)
        if (within != null && !area.intersect(within)) return
        if (area.isEmpty || pitchX <= 0f || pitchY <= 0f) return
        val firstX = ceil((area.left - page.left) / pitchX).toInt()
        val lastX = floor((area.right - page.left) / pitchX).toInt()
        val firstY = ceil((area.top - page.top) / pitchY).toInt()
        val lastY = floor((area.bottom - page.top) / pitchY).toInt()
        val count = (lastX - firstX + 1).coerceAtLeast(0) + (lastY - firstY + 1).coerceAtLeast(0)
        if (count == 0) return
        if (buf.size < count * 4) buf = FloatArray(count * 4)
        var i = 0
        for (k in firstX..lastX) {
            val x = page.left + k * pitchX
            buf[i++] = x
            buf[i++] = area.top
            buf[i++] = x
            buf[i++] = area.bottom
        }
        for (k in firstY..lastY) {
            val y = page.top + k * pitchY
            buf[i++] = area.left
            buf[i++] = y
            buf[i++] = area.right
            buf[i++] = y
        }
        canvas.drawLines(buf, 0, i, paint)
    }

    companion object {
        /** Lightgrey: reads lightly on e-ink but stays visible. */
        const val COLOR = 0xFFD3D3D3.toInt()
        const val PITCH_MM = 5f
        private const val MM_PER_INCH = 25.4f

        /** Pixels of [PITCH_MM] on a display of [dpi] pixels per inch. */
        fun pitchPx(dpi: Float): Float = PITCH_MM / MM_PER_INCH * dpi
    }
}
