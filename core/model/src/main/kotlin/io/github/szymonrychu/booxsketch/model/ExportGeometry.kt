package io.github.szymonrychu.booxsketch.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Axis-aligned rectangle in canvas pixels. */
data class RectF(
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
)

/**
 * Where to crop the canvas and how to scale it for upload. The output is at least
 * [outW] x [outH]; when the scaled crop is smaller than that, the renderer pads it with white.
 */
data class ExportPlan(
    val cropLeft: Int,
    val cropTop: Int,
    val cropW: Int,
    val cropH: Int,
    val scale: Float,
    val outW: Int,
    val outH: Int,
)

/** Plans the PNG export of a page; null when there are no strokes. Never upscales. */
fun planExport(
    strokesBounds: RectF?,
    canvas: Canvas,
    maxLongEdge: Int,
    minEdge: Int = 200,
    margin: Int = 24,
): ExportPlan? {
    if (strokesBounds == null) return null
    val left = max(0, floor(strokesBounds.l - margin).toInt())
    val top = max(0, floor(strokesBounds.t - margin).toInt())
    val right = min(canvas.w, ceil(strokesBounds.r + margin).toInt())
    val bottom = min(canvas.h, ceil(strokesBounds.b + margin).toInt())
    val cropW = max(1, right - left)
    val cropH = max(1, bottom - top)
    val longEdge = max(cropW, cropH)
    val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1f
    val outW = max(minEdge, (cropW * scale).roundToInt()).coerceAtMost(maxLongEdge)
    val outH = max(minEdge, (cropH * scale).roundToInt()).coerceAtMost(maxLongEdge)
    return ExportPlan(left, top, cropW, cropH, scale, outW, outH)
}
