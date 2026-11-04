package io.github.szymonrychu.booxsketch.model

import kotlin.math.hypot

/** Ids of strokes whose polyline passes within [radius] plus half the stroke width of (x, y). */
fun hitStrokes(
    strokes: List<Stroke>,
    x: Float,
    y: Float,
    radius: Float,
): Set<String> =
    strokes
        .filter { s ->
            val reach = radius + s.width / 2f
            shapeOutline(s).any { line ->
                when {
                    line.isEmpty() -> false
                    line.size == 1 -> hypot(line[0].x - x, line[0].y - y) <= reach
                    else -> line.zipWithNext().any { (a, b) -> distToSegment(x, y, a, b) <= reach }
                }
            }
        }.map { it.id }
        .toSet()

private fun distToSegment(
    px: Float,
    py: Float,
    a: Vec,
    b: Vec,
): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
    return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
}
