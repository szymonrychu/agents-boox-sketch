package io.github.szymonrychu.booxsketch.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.Vec
import io.github.szymonrychu.booxsketch.model.arrowHead
import io.github.szymonrychu.booxsketch.model.rectOf

/** Draws strokes onto an Android canvas: canvas px * [scale] + ([dx], [dy]). */
interface StrokeRenderer {
    fun draw(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    )
}

class CanvasStrokeRenderer : StrokeRenderer {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            color = Color.BLACK
        }

    override fun draw(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        for (s in strokes) drawStroke(canvas, s, scale, dx, dy)
    }

    private fun drawStroke(
        canvas: Canvas,
        s: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        if (s.points.isEmpty()) return
        if (s.tool.isShape) {
            if (s.points.size >= 2) drawShape(canvas, s, scale, dx, dy)
            return
        }
        val pts = s.points
        if (pts.size == 1) {
            val p = pts[0]
            style(s, p)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(p.x * scale + dx, p.y * scale + dy, paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            style(s, b, scale, avg = a)
            canvas.drawLine(a.x * scale + dx, a.y * scale + dy, b.x * scale + dx, b.y * scale + dy, paint)
        }
    }

    private fun drawShape(
        canvas: Canvas,
        s: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        style(s, s.points.first(), scale)
        if (s.tool == Tool.RECTANGLE) {
            val r = rectOf(s)
            canvas.drawRect(r.l * scale + dx, r.t * scale + dy, r.r * scale + dx, r.b * scale + dy, paint)
            return
        }
        paint.strokeCap = Paint.Cap.ROUND // arrow shaft and head lines end in round caps
        val tail = Vec(s.points.first().x, s.points.first().y)
        val tip = Vec(s.points.last().x, s.points.last().y)
        val (w1, w2) = arrowHead(tail, tip, s.width)
        for ((a, b) in listOf(tail to tip, tip to w1, tip to w2)) {
            canvas.drawLine(a.x * scale + dx, a.y * scale + dy, b.x * scale + dx, b.y * scale + dy, paint)
        }
    }

    private fun style(
        s: Stroke,
        p: Point,
        scale: Float = 1f,
        avg: Point? = null,
    ) {
        val pressure = (if (avg == null) p.pressure else (p.pressure + avg.pressure) / 2f).coerceIn(0f, 1f)
        val (w, alpha) =
            when (s.tool) {
                Tool.FOUNTAIN -> s.width * (0.35f + 0.65f * pressure) to 255
                Tool.BRUSH -> s.width * (0.2f + 0.8f * pressure) to 255
                Tool.BALLPOINT, Tool.RECTANGLE, Tool.ARROW -> s.width to 255
                Tool.CHARCOAL -> s.width to (110 + 100 * pressure).toInt()
                Tool.CHARCOAL_V2 -> s.width * 1.2f to (80 + 90 * pressure).toInt()
                Tool.MARKER -> s.width * 3f to 90
            }
        paint.strokeWidth = (w * scale).coerceAtLeast(1f)
        paint.color = s.color.argb
        paint.alpha = alpha
    }
}
