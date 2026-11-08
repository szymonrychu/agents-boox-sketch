package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.RectF
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Vec
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/** A freehand closed curve drawn with a pen tool: its outline and bounding box in page pixels. */
data class Loop(
    val strokeId: String,
    val polygon: List<Vec>,
    val bounds: RectF,
)

/**
 * A freehand arrow: the shaft stroke plus the stroke(s) of its head, in drawing order. [tip] is the shaft end
 * at the head, [tail] the other end. A [doubleHeaded] arrow has a head at both ends, so either end may be the tip.
 */
data class FreehandArrow(
    val strokeIds: List<String>,
    val tail: Vec,
    val tip: Vec,
    val doubleHeaded: Boolean = false,
)

/**
 * Recognises margin-insert marks drawn freehand with a pen: closed loops and arrows. Pure geometry over page
 * pixels; thresholds are in cells ([NotebookGeometry.CELL]) so they follow the page scale.
 */
@Suppress("TooManyFunctions") // one small geometric check per shape (loop, hook, V head, tick head)
object FreehandShapes {
    private const val CELL = NotebookGeometry.CELL.toFloat()

    private const val TAIL_FRACTION = 0.15f
    private const val MIN_LOOP_AREA = 1.5f * 1.5f * CELL * CELL
    private const val MAX_LOOP_PATH_RATIO = 1.8f

    private const val MIN_SHAFT = 2f * CELL
    private const val MAX_SHAFT_DEVIATION = 0.25f
    private const val MAX_SHAFT_PATH_RATIO = 1.5f
    private const val MAX_START_TURN_DEG = 45.0
    private const val SHAFT_START_FRACTION = 0.2f

    private const val HOOK_FRACTION = 0.2f
    private const val HOOK_MIN_FRACTION = 0.05f
    private const val HOOK_MIN_TURN_DEG = 100.0
    private const val HOOK_MAX_TURN_DEG = 170.0
    private const val MIN_ARM = 8f
    private const val MIN_HOOK_ARM = 0.25f * CELL
    private const val MIN_HOOK_ARM_FRACTION = 0.05f
    private const val MAX_CLOSE_GAP_FRACTION = 0.35f

    private const val MAX_HEAD_STROKE = 0.6f * CELL
    private const val HEAD_TOUCH = 0.3f * CELL
    private const val MIN_TICK_ARM = 0.1f * CELL
    private const val MAX_TICK_ANGLE_DEG = 120.0
    private const val MAX_TICK_BISECTOR_DEG = 60.0
    private const val TICK_BACK_REACH = 0.5f * CELL
    private const val MAX_HEAD_STROKES = 2

    /** The loop drawn by [stroke], or null when it is not a freehand closed curve of at least ~1.5 cells. */
    fun loop(stroke: Stroke): Loop? {
        if (stroke.tool.isShape || stroke.points.size < MIN_LOOP_POINTS) return null
        val pts = stroke.points.map { Vec(it.x, it.y) }
        val polygon = closedCandidates(pts).firstOrNull { isLoopShaped(it) } ?: return null
        return Loop(stroke.id, polygon, boundsOf(polygon))
    }

    private fun isLoopShaped(polygon: List<Vec>): Boolean {
        val bounds = boundsOf(polygon)
        val w = bounds.r - bounds.l
        val h = bounds.b - bounds.t
        return w >= CELL && h >= CELL && area(polygon) >= MIN_LOOP_AREA && pathLength(polygon) <= MAX_LOOP_PATH_RATIO * 2f * (w + h)
    }

    /** The arrow whose shaft is `strokes[startIndex]`, with its head taken from the strokes that follow. */
    fun arrow(
        strokes: List<Stroke>,
        startIndex: Int,
    ): FreehandArrow? {
        val shaft = strokes.getOrNull(startIndex)?.takeIf { !it.tool.isShape && it.points.size >= MIN_ARROW_POINTS }
        return shaft?.let { hookArrow(it) ?: separateHeadArrow(strokes, startIndex) }
    }

    // --- loops ---

    /**
     * Outlines a stroke may close on: the part after a self-crossing in its last 15 percent, then the whole path
     * when the end lies within a cell (and within 35 percent of the stroke's larger bounds side) of the start.
     */
    private fun closedCandidates(pts: List<Vec>): Sequence<List<Vec>> =
        sequence {
            val tailStart = max(1, (pts.size * (1f - TAIL_FRACTION)).toInt())
            for (i in tailStart until pts.size) {
                for (j in 0 until i - 2) {
                    val x = intersection(pts[j], pts[j + 1], pts[i - 1], pts[i]) ?: continue
                    yield(listOf(x) + pts.subList(j + 1, i) + x)
                }
            }
            val bounds = boundsOf(pts)
            val closeGap = minOf(CELL, MAX_CLOSE_GAP_FRACTION * max(bounds.r - bounds.l, bounds.b - bounds.t))
            // The end returns to a point in the first half of the path (a lead-in or overshoot is cut off).
            val closeIndex = (0..pts.size / 2).firstOrNull { dist(pts[it], pts.last()) < closeGap }
            if (closeIndex != null) yield(pts.subList(closeIndex, pts.size))
        }

    // --- arrows ---

    /** Shaft and head in one stroke: the last 20 percent of the path turns back 100-170 degrees at the tip, with an
     * arm of at least a quarter cell or 5 percent of the stroke's larger bounds side. */
    private fun hookArrow(stroke: Stroke): FreehandArrow? {
        val pts = stroke.points.map { Vec(it.x, it.y) }
        val total = pathLength(pts)
        val arcToEnd = arcLengthsToEnd(pts)
        val candidates =
            pts.indices.filter { arcToEnd[it] in total * HOOK_MIN_FRACTION..total * HOOK_FRACTION }
        val tipIndex = candidates.maxByOrNull { dist(pts[0], pts[it]) } ?: return null
        val tip = pts[tipIndex]
        val head = pts.last()
        val shaft = pts.subList(0, tipIndex + 1)
        val shaftDir = Vec(tip.x - pts[0].x, tip.y - pts[0].y)
        val headDir = Vec(head.x - tip.x, head.y - tip.y)
        val turn = angleDeg(shaftDir, headDir)
        val bounds = boundsOf(pts)
        val ok =
            dist(tip, head) >= max(MIN_HOOK_ARM, MIN_HOOK_ARM_FRACTION * max(bounds.r - bounds.l, bounds.b - bounds.t)) &&
                turn in HOOK_MIN_TURN_DEG..HOOK_MAX_TURN_DEG &&
                isStraightShaft(shaft)
        return if (ok) FreehandArrow(listOf(stroke.id), pts[0], tip) else null
    }

    /** Straight shaft stroke plus a V head from one or two following strokes at either shaft end, or else a one-stroke
     * tick or caret head whose sharp vertex sits on a shaft end. */
    private fun separateHeadArrow(
        strokes: List<Stroke>,
        startIndex: Int,
    ): FreehandArrow? {
        val shaftStroke = strokes[startIndex]
        val shaft = shaftStroke.points.map { Vec(it.x, it.y) }
        if (!isStraightShaft(shaft)) return null
        val rest = strokes.drop(startIndex + 1)
        val ends = listOf(shaft.last() to shaft.first(), shaft.first() to shaft.last())
        for (count in 1..MAX_HEAD_STROKES) {
            for ((tip, tail) in ends) {
                val heads = headAt(rest.take(count), count, tip, tail) ?: continue
                // A double-headed arrow: a second head at the other end is consumed too.
                val second = (1..MAX_HEAD_STROKES).firstNotNullOfOrNull { c -> headAt(rest.drop(count).take(c), c, tail, tip) }
                return FreehandArrow(listOf(shaftStroke.id) + (heads + second.orEmpty()).map { it.id }, tail, tip, second != null)
            }
        }
        val next = rest.firstOrNull() ?: return null
        return ends.firstNotNullOfOrNull { (tip, tail) ->
            // Walk the shaft from the tip so a curved shaft is judged by its direction at the tip, not by its chord.
            val fromTip = if (tip == shaft.last()) shaft.asReversed() else shaft
            val back = fromTip.firstOrNull { dist(it, tip) >= TICK_BACK_REACH } ?: tail
            FreehandArrow(listOf(shaftStroke.id, next.id), tail, tip).takeIf { isTickHead(next, tip, back) }
        }
    }

    /** [heads] when they form a V head of [count] strokes with its vertex at [tip], opening towards [tail]. */
    private fun headAt(
        heads: List<Stroke>,
        count: Int,
        tip: Vec,
        tail: Vec,
    ): List<Stroke>? {
        val headPts = heads.map { s -> s.points.map { Vec(it.x, it.y) } }
        // A one-stroke chevron holds both arms, so it may be twice as long as one arm stroke.
        val limit = MAX_HEAD_STROKE * (3 - count)
        val usable = heads.size == count && heads.none { it.tool.isShape } && headPts.none { it.size < 2 || pathLength(it) >= limit }
        return heads.takeIf { usable && isVHead(headPts, tip, tail) }
    }

    /** A tick or caret: at most two cells of path, its sharp interior vertex within [HEAD_TOUCH] of [tip], both arms at
     * least [MIN_TICK_ARM] long, under [MAX_TICK_ANGLE_DEG] apart and opening back along the shaft (bisector within
     * [MAX_TICK_BISECTOR_DEG] of the direction from [tip] to [back], a shaft point half a cell behind it). The vertex
     * check keeps curves and strokes that merely pass by out. */
    private fun isTickHead(
        head: Stroke,
        tip: Vec,
        back: Vec,
    ): Boolean {
        val h = head.points.map { Vec(it.x, it.y) }
        if (head.tool.isShape || h.size < 3 || pathLength(h) > 2f * CELL) return false
        val v = h.indices.minBy { dist(h[it], tip) }
        if (dist(h[v], tip) > HEAD_TOUCH || v == 0 || v == h.lastIndex) return false
        val a = Vec(h.first().x - h[v].x, h.first().y - h[v].y)
        val b = Vec(h.last().x - h[v].x, h.last().y - h[v].y)
        val la = hypot(a.x, a.y)
        val lb = hypot(b.x, b.y)
        if (la < MIN_TICK_ARM || lb < MIN_TICK_ARM || angleDeg(a, b) >= MAX_TICK_ANGLE_DEG) return false
        // Both arms point back along the shaft: their bisector is roughly opposite the shaft direction at the tip.
        val bisector = Vec(a.x / la + b.x / lb, a.y / la + b.y / lb)
        return angleDeg(bisector, Vec(back.x - tip.x, back.y - tip.y)) <= MAX_TICK_BISECTOR_DEG
    }

    private fun isStraightShaft(pts: List<Vec>): Boolean {
        if (pts.size < 2) return false
        val a = pts.first()
        val b = pts.last()
        val chord = dist(a, b)
        val total = pathLength(pts)
        val meanDev = pts.sumOf { lineDistance(it, a, b).toDouble() }.toFloat() / pts.size
        val straight = chord >= MIN_SHAFT && meanDev < MAX_SHAFT_DEVIATION * chord && total <= MAX_SHAFT_PATH_RATIO * chord
        return straight && startsAlongChord(pts, total)
    }

    /** The first fifth of the path heads the same way as the chord (no hooked tail). */
    private fun startsAlongChord(
        pts: List<Vec>,
        total: Float,
    ): Boolean {
        val a = pts.first()
        val b = pts.last()
        val arc = arcLengthsToEnd(pts)
        val early = pts.indices.firstOrNull { total - arc[it] >= total * SHAFT_START_FRACTION } ?: return false
        val turn = angleDeg(Vec(b.x - a.x, b.y - a.y), Vec(pts[early].x - a.x, pts[early].y - a.y))
        return turn <= MAX_START_TURN_DEG
    }

    /** One chevron stroke (vertex near [tip]) or two arm strokes (each touching [tip]) opening towards [tail]. */
    private fun isVHead(
        heads: List<List<Vec>>,
        tip: Vec,
        tail: Vec,
    ): Boolean {
        val arms: List<Vec> =
            if (heads.size == 1) {
                val s = heads[0]
                val vertex = s.indices.minBy { dist(s[it], tip) }
                if (dist(s[vertex], tip) > HEAD_TOUCH || vertex == 0 || vertex == s.lastIndex) return false
                listOf(s.first(), s.last())
            } else {
                val rooted = heads.map { s -> if (dist(s.first(), tip) <= dist(s.last(), tip)) s else s.reversed() }
                if (rooted.any { dist(it.first(), tip) > HEAD_TOUCH }) return false
                rooted.map { it.last() }
            }
        val axis = Vec(tail.x - tip.x, tail.y - tip.y)
        val rel = arms.map { Vec(it.x - tip.x, it.y - tip.y) }
        val away = rel.all { hypot(it.x, it.y) >= MIN_ARM && dot(it, axis) > 0f }
        val sides = rel.map { axis.x * it.y - axis.y * it.x }
        return away && sides[0] * sides[1] < 0f
    }

    private const val MIN_LOOP_POINTS = 8
    private const val MIN_ARROW_POINTS = 3
}

private const val EPS = 1e-6f

private fun dist(
    a: Vec,
    b: Vec,
): Float = hypot(a.x - b.x, a.y - b.y)

private fun dot(
    a: Vec,
    b: Vec,
): Float = a.x * b.x + a.y * b.y

private fun angleDeg(
    a: Vec,
    b: Vec,
): Double {
    val la = hypot(a.x, a.y)
    val lb = hypot(b.x, b.y)
    if (la < EPS || lb < EPS) return 0.0
    val c = (dot(a, b) / (la * lb)).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(c.toDouble()))
}

private fun lineDistance(
    p: Vec,
    a: Vec,
    b: Vec,
): Float {
    val len = dist(a, b)
    return if (len < EPS) dist(p, a) else abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / len
}

private fun pathLength(pts: List<Vec>): Float {
    var s = 0f
    for (i in 1 until pts.size) s += dist(pts[i - 1], pts[i])
    return s
}

private fun arcLengthsToEnd(pts: List<Vec>): FloatArray {
    val out = FloatArray(pts.size)
    for (i in pts.size - 2 downTo 0) out[i] = out[i + 1] + dist(pts[i], pts[i + 1])
    return out
}

private fun intersection(
    a: Vec,
    b: Vec,
    c: Vec,
    d: Vec,
): Vec? {
    val rx = b.x - a.x
    val ry = b.y - a.y
    val sx = d.x - c.x
    val sy = d.y - c.y
    val den = rx * sy - ry * sx
    if (abs(den) < EPS) return null
    val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / den
    val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / den
    return if (t in 0f..1f && u in 0f..1f) Vec(a.x + t * rx, a.y + t * ry) else null
}

private fun area(poly: List<Vec>): Float {
    var s = 0f
    for (i in poly.indices) {
        val p = poly[i]
        val q = poly[(i + 1) % poly.size]
        s += p.x * q.y - q.x * p.y
    }
    return abs(s) / 2f
}

private fun boundsOf(pts: List<Vec>): RectF = RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
