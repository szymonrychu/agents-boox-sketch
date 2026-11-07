@file:Suppress("MatchingDeclarationName")

package io.github.szymonrychu.booxsketch.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A 2D position in canvas pixels. */
data class Vec(
    val x: Float,
    val y: Float,
)

/** Arrow ends snap to rectangle anchors within this many canvas pixels. */
const val SNAP_RADIUS = 40f

/** Shapes with both extents below this many canvas pixels are discarded. */
const val MIN_SHAPE_SIZE = 8f

private const val HEAD_MIN_LENGTH = 16f
private const val HEAD_WIDTH_FACTOR = 4f
private const val HEAD_HALF_ANGLE_DEG = 28.0

/** Bounding box of the first and last point of a shape stroke. */
fun rectOf(s: Stroke): RectF {
    val a = s.points.first()
    val b = s.points.last()
    return RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
}

/** Corners (tl, tr, br, bl) then side midpoints (top, right, bottom, left). */
fun rectAnchors(r: RectF): List<Vec> {
    val mx = (r.l + r.r) / 2f
    val my = (r.t + r.b) / 2f
    return listOf(
        Vec(r.l, r.t),
        Vec(r.r, r.t),
        Vec(r.r, r.b),
        Vec(r.l, r.b),
        Vec(mx, r.t),
        Vec(r.r, my),
        Vec(mx, r.b),
        Vec(r.l, my),
    )
}

/**
 * Turns a drag into a final 2-point shape stroke, or null when it is too small or not a shape.
 * Arrow ends snap to the nearest rectangle anchor in [page] within [radius]; when both ends would
 * land on the same anchor the tip keeps its raw position.
 */
fun snapShape(
    drag: Stroke,
    page: List<Stroke>,
    radius: Float = SNAP_RADIUS,
): Stroke? {
    if (!drag.tool.isShape || drag.points.size < 2) return null
    val first = drag.points.first()
    val last = drag.points.last()
    val (tail, tip) =
        if (drag.tool.isBox) {
            val r = rectOf(drag)
            Vec(r.l, r.t) to Vec(r.r, r.b)
        } else {
            val anchors = page.filter { it.tool == Tool.RECTANGLE && it.points.size >= 2 }.flatMap { rectAnchors(rectOf(it)) }
            val rawTail = Vec(first.x, first.y)
            val rawTip = Vec(last.x, last.y)
            val snappedTail = nearest(anchors, rawTail, radius) ?: rawTail
            val snappedTip = nearest(anchors, rawTip, radius) ?: rawTip
            snappedTail to if (snappedTip == snappedTail) rawTip else snappedTip
        }
    if (max(abs(tip.x - tail.x), abs(tip.y - tail.y)) < MIN_SHAPE_SIZE) return null
    return drag.copy(
        points =
            listOf(
                Point(tail.x, tail.y, 1f, first.tMs),
                Point(tip.x, tip.y, 1f, last.tMs),
            ),
    )
}

private fun nearest(
    anchors: List<Vec>,
    p: Vec,
    radius: Float,
): Vec? {
    var best: Vec? = null
    var bestD = Float.MAX_VALUE
    for (a in anchors) {
        val d = hypot(a.x - p.x, a.y - p.y)
        if (d <= radius && d < bestD) {
            best = a
            bestD = d
        }
    }
    return best
}

/** Wing end points of an arrow head at [tip], pointing back toward [tail]. */
fun arrowHead(
    tail: Vec,
    tip: Vec,
    width: Float,
): Pair<Vec, Vec> {
    val len = max(HEAD_MIN_LENGTH, width * HEAD_WIDTH_FACTOR)
    val back = atan2(tail.y - tip.y, tail.x - tip.x)
    val half = Math.toRadians(HEAD_HALF_ANGLE_DEG)

    fun wing(a: Double) = Vec(tip.x + len * cos(a).toFloat(), tip.y + len * sin(a).toFloat())
    return wing(back + half) to wing(back - half)
}

/** Polylines that make up a stroke's visible outline: freehand path, rectangle loop, arrow shaft and head. */
fun shapeOutline(s: Stroke): List<List<Vec>> {
    val pts = s.points.map { Vec(it.x, it.y) }
    if (!s.tool.isShape || pts.size < 2) return listOf(pts)
    return when (s.tool) {
        Tool.RECTANGLE, Tool.IMAGE_REGION -> {
            val r = rectOf(s)
            listOf(
                listOf(Vec(r.l, r.t), Vec(r.r, r.t), Vec(r.r, r.b), Vec(r.l, r.b), Vec(r.l, r.t)),
            )
        }

        else -> {
            val tail = pts.first()
            val tip = pts.last()
            val (w1, w2) = arrowHead(tail, tip, s.width)
            listOf(listOf(tail, tip), listOf(tip, w1), listOf(tip, w2))
        }
    }
}
