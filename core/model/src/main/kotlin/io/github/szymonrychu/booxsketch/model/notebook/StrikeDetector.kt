package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Stroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Strike-through and scribble-out over a text band (spec 3.5). A strike is a pen stroke whose bounding box lies
 * mostly inside the band, under [MAX_HEIGHT] x the band tall, wider than [MIN_ASPECT] x its height and on average
 * closer than [MAX_DEVIATION] x the band to its own chord. A scribble (zig-zag) is accepted when it stays inside the
 * band and turns back at least [MIN_TURNS] times. Both must be at least [MIN_WIDTH] wide, so a dot is never a strike.
 * Whether the stroke crosses words is the caller's test.
 */
object StrikeDetector {
    const val MAX_HEIGHT = 0.40f
    const val MIN_ASPECT = 1.5f
    const val MAX_DEVIATION = 0.25f
    const val MIN_TURNS = 3

    /** Narrowest strike, page px (0.3 cell). */
    const val MIN_WIDTH = 0.3f * NotebookGeometry.CELL

    /** A scribble may stick out of the band by this fraction of it. */
    private const val SCRIBBLE_SLACK = 0.10f

    /** A direction change counts once the pen moved back this fraction of the band. */
    private const val TURN_HYSTERESIS = 0.15f

    /** True when [stroke] strikes through or scribbles out text in the band spanning page y [band]. */
    fun isStrike(
        stroke: Stroke,
        band: ClosedFloatingPointRange<Float>,
    ): Boolean {
        val pts = stroke.points
        if (stroke.tool.isShape || pts.isEmpty()) return false
        val bandH = band.endInclusive - band.start
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val width = pts.maxOf { it.x } - pts.minOf { it.x }
        val height = maxY - minY
        if (width < MIN_WIDTH) return false
        val flat =
            mostlyInside(minY, maxY, band) && height < MAX_HEIGHT * bandH && width > MIN_ASPECT * height &&
                meanChordDeviation(stroke) < MAX_DEVIATION * bandH
        if (flat) return true
        val slack = SCRIBBLE_SLACK * bandH
        val inside = minY >= band.start - slack && maxY <= band.endInclusive + slack
        return inside && turns(stroke, TURN_HYSTERESIS * bandH) >= MIN_TURNS
    }

    /** At least half of the box height is in the band (a flat box: its centre is). */
    private fun mostlyInside(
        minY: Float,
        maxY: Float,
        band: ClosedFloatingPointRange<Float>,
    ): Boolean {
        val height = maxY - minY
        if (height <= 0f) return minY in band
        val overlap = minOf(maxY, band.endInclusive) - maxOf(minY, band.start)
        return overlap >= height / 2f
    }

    /** Mean distance of the points from the line through the first and last point (from the first, when they meet). */
    private fun meanChordDeviation(stroke: Stroke): Float {
        val a = stroke.points.first()
        val b = stroke.points.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        return stroke.points
            .map { p -> if (len < 1f) hypot(p.x - a.x, p.y - a.y) else abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len }
            .average()
            .toFloat()
    }

    /** Direction reversals along x plus those along y, each counted once the pen moved back [hysteresis] px. */
    private fun turns(
        stroke: Stroke,
        hysteresis: Float,
    ): Int = reversals(stroke.points.map { it.x }, hysteresis) + reversals(stroke.points.map { it.y }, hysteresis)

    private fun reversals(
        values: List<Float>,
        hysteresis: Float,
    ): Int {
        var count = 0
        var dir = 0
        // The farthest value in the current direction; before any direction, the start.
        var far = values.first()
        for (v in values) {
            val step = v - far
            when {
                dir == 0 && abs(step) >= hysteresis -> {
                    dir = sign(step).toInt()
                    far = v
                }

                step * dir > 0 -> {
                    far = v
                }

                dir != 0 && -step * dir >= hysteresis -> {
                    count++
                    dir = -dir
                    far = v
                }
            }
        }
        return count
    }
}
