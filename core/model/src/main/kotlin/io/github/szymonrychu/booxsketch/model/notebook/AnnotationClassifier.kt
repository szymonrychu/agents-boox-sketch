package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.RectF
import io.github.szymonrychu.booxsketch.model.SNAP_RADIUS
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.Vec
import io.github.szymonrychu.booxsketch.model.rectAnchors
import io.github.szymonrychu.booxsketch.model.rectOf
import kotlin.math.hypot

/**
 * A margin insert mark (spec 3.7): the box around the note, the arrow(s) from it, the arrow tip on the text and the
 * note strokes inside the box. Page px.
 */
data class InsertMark(
    val boxStrokeId: String,
    val arrowStrokeIds: List<String>,
    val rect: RectF,
    val tip: Vec,
    val noteStrokeIds: List<String>,
)

/** Finds insert marks among the [candidates] strokes of [page] (not consumed, not pending). */
fun interface InsertMarkFinder {
    fun find(
        page: Page,
        candidates: List<Stroke>,
        geometry: NotebookGeometry,
    ): List<InsertMark>
}

/** An arrow usable as an insert mark's pointer: the tool arrow or a freehand one. [tail] starts at the note. */
internal data class InsertArrow(
    val strokeIds: List<String>,
    val tail: Vec,
    val tip: Vec,
)

/**
 * Tool arrows and freehand arrows among [candidates], in drawing order of their shaft. A double-headed arrow is listed
 * once per orientation; the finders' `used` sets stop it being claimed twice.
 */
internal fun insertArrows(candidates: List<Stroke>): List<InsertArrow> =
    candidates.flatMapIndexed { i, s ->
        when {
            s.tool == Tool.ARROW && s.points.size >= 2 -> {
                listOf(InsertArrow(listOf(s.id), s.points.first().let { Vec(it.x, it.y) }, s.points.last().let { Vec(it.x, it.y) }))
            }

            !s.tool.isShape -> {
                val a = FreehandShapes.arrow(candidates, i)
                when {
                    a == null -> emptyList()
                    a.doubleHeaded -> listOf(InsertArrow(a.strokeIds, a.tail, a.tip), InsertArrow(a.strokeIds, a.tip, a.tail))
                    else -> listOf(InsertArrow(a.strokeIds, a.tail, a.tip))
                }
            }

            else -> {
                emptyList()
            }
        }
    }

/**
 * Insert marks drawn with the shape tools (Q12 B): a [Tool.RECTANGLE] lying in the margin (at least 80 percent of its
 * width right of the margin line) with pen strokes centred inside it, and an [Tool.ARROW] or freehand arrow whose tail lies
 * within [SNAP_RADIUS] of one of its anchors and whose tip is in the text column on a row. One arrow per box: the first one
 * drawn.
 */
object ToolInsertMarks : InsertMarkFinder {
    private const val IN_MARGIN = 0.8f

    override fun find(
        page: Page,
        candidates: List<Stroke>,
        geometry: NotebookGeometry,
    ): List<InsertMark> {
        val arrows = insertArrows(candidates)
        val pens = candidates.filterNot { it.tool.isShape }
        val used = HashSet<String>()
        return candidates
            .filter { it.tool == Tool.RECTANGLE && it.points.size >= 2 }
            .mapNotNull { box ->
                val r = rectOf(box)
                if (r.r - maxOf(r.l, geometry.marginX.toFloat()) < IN_MARGIN * (r.r - r.l)) return@mapNotNull null
                val anchors = rectAnchors(r)
                val arrow =
                    arrows.firstOrNull { a ->
                        a.strokeIds.none { it in used } && anchors.any { hypot(it.x - a.tail.x, it.y - a.tail.y) <= SNAP_RADIUS } &&
                            geometry.inTextColumn(a.tip.x) && geometry.rowAt(a.tip.y) != null
                    } ?: return@mapNotNull null
                val notes = pens.filter { s -> s.id !in arrow.strokeIds && centre(s).let { it.x in r.l..r.r && it.y in r.t..r.b } }
                if (notes.isEmpty()) return@mapNotNull null
                used += arrow.strokeIds
                InsertMark(box.id, arrow.strokeIds, r, arrow.tip, notes.map { it.id })
            }
    }
}

/**
 * Insert marks drawn freehand (Q12 C): a pen [Loop] in the margin (at least 80 percent of its bounds right of the margin
 * line) around pen strokes centred inside its polygon (the note), and a [Tool.ARROW] or freehand arrow whose tail lies
 * within [SNAP_RADIUS] of the loop outline and whose tip is in the text column on a row. One arrow per loop: the
 * first one drawn. Loop and arrow strokes are never notes.
 */
object FreehandInsertMarks : InsertMarkFinder {
    private const val IN_MARGIN = 0.8f

    override fun find(
        page: Page,
        candidates: List<Stroke>,
        geometry: NotebookGeometry,
    ): List<InsertMark> {
        val arrows = insertArrows(candidates)
        val used = HashSet<String>()
        return candidates
            .filter { !it.tool.isShape }
            .mapNotNull { FreehandShapes.loop(it) }
            .mapNotNull { loop ->
                val b = loop.bounds
                if (b.r - maxOf(b.l, geometry.marginX.toFloat()) < IN_MARGIN * (b.r - b.l)) return@mapNotNull null
                val arrow =
                    arrows.firstOrNull { a ->
                        loop.strokeId !in a.strokeIds && a.strokeIds.none { it in used } &&
                            outlineDistance(loop.polygon, a.tail) <= SNAP_RADIUS &&
                            geometry.inTextColumn(a.tip.x) && geometry.rowAt(a.tip.y) != null
                    } ?: return@mapNotNull null
                val notes =
                    candidates.filter { s ->
                        !s.tool.isShape && s.id != loop.strokeId && s.id !in arrow.strokeIds && inside(loop.polygon, centre(s))
                    }
                if (notes.isEmpty()) return@mapNotNull null
                used += arrow.strokeIds
                InsertMark(loop.strokeId, arrow.strokeIds, b, arrow.tip, notes.map { it.id })
            }
    }

    private fun outlineDistance(
        polygon: List<Vec>,
        p: Vec,
    ): Float = polygon.zipWithNext().minOfOrNull { (a, c) -> segmentDistance(a, c, p) } ?: Float.MAX_VALUE

    private fun segmentDistance(
        a: Vec,
        b: Vec,
        p: Vec,
    ): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Even-odd point in polygon. */
    private fun inside(
        polygon: List<Vec>,
        p: Vec,
    ): Boolean {
        var odd = false
        for ((a, b) in polygon.zipWithNext()) {
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) odd = !odd
        }
        return odd
    }
}

/**
 * The edit ops of a page, the strokes they use, the margin strokes that are no edit op, and the [OpKind.NOTE] ops that
 * carry that margin ink (one per cluster, with the arrows drawn from it) to the translator.
 */
data class Classification(
    val ops: List<EditOp>,
    val opStrokeIds: Set<String>,
    val marginInk: List<String>,
    val notes: List<EditOp> = emptyList(),
)

/**
 * Classifies the handwriting of a notebook page with text runs into edit ops (spec 4.3, rules 2-4). Candidates are the
 * visible strokes not in a pending segment or op, not rejected and not in `exclude` (the ink of image regions). In
 * order: margin inserts ([finders]; the arrow tip on a text band inserts inline at the nearest word boundary, anywhere
 * else after the nearest run above), strikes ([StrikeDetector] over a text band crossing words: delete those words;
 * strikes over the same words of a run are one op), replaces (pen strokes centred in an annotation band, grouped by x
 * with a gap over one cell, over the words of a run on that row: replace those words). Targets are whole words, as
 * source offsets with the source text as quote. The ops come ordered by their line (row, then x0) and by x within a
 * line (Q13 A). Margin strokes outside any insert are [Classification.marginInk]; they become note ops before strikes
 * and replaces run, so the arrow from a note is never read as a strike. Everything else is new handwriting for the
 * [Segmenter].
 */
class AnnotationClassifier(
    private val finders: List<InsertMarkFinder>,
) {
    fun classify(
        page: Page,
        geometry: NotebookGeometry,
        measure: TextMeasure,
        exclude: Set<String> = emptySet(),
        newId: () -> String,
    ): Classification {
        val skip =
            exclude + page.rejectedStrokes + page.pendingSegments.flatMap { it.strokeIds } +
                page.pendingOps.flatMap { it.op.strokeIds }
        val candidates = page.visibleStrokes.filter { it.points.isNotEmpty() && it.id !in skip }
        val boxes = page.text.associateWith { WordBoxes.of(it, geometry, measure) }
        val ops = ArrayList<Pair<Placed, EditOp>>()
        val used = HashSet<String>()
        if (boxes.isNotEmpty()) {
            for (mark in finders.flatMap { it.find(page, candidates, geometry) }) {
                val ids = mark.noteStrokeIds + mark.boxStrokeId + mark.arrowStrokeIds
                val resolved = if (ids.none { it in used }) resolveTip(mark.tip, boxes, geometry) else null
                if (resolved != null) {
                    used += ids
                    val rect = listOf(mark.rect.l, mark.rect.t, mark.rect.r - mark.rect.l, mark.rect.b - mark.rect.t)
                    val op = EditOp(newId(), OpKind.INSERT, at = resolved.first, strokeIds = ids, rect = rect)
                    ops += Placed(resolved.second, mark.tip.x) to op
                }
            }
        }
        val margin = candidates.filter { it.id !in used && geometry.inMargin(centre(it).x) }
        val notes = notes(margin, candidates.filter { it.id !in used }, geometry, newId)
        val noted = notes.flatMap { it.strokeIds }.toSet()
        if (boxes.isNotEmpty()) {
            val free = { candidates.filter { it.id !in used && it.id !in noted } }
            ops += strikes(free(), boxes, geometry, newId).onEach { used += it.second.strokeIds }
            ops += replaces(free(), boxes, geometry, newId).onEach { used += it.second.strokeIds }
        }
        val ordered = ops.sortedWith(compareBy({ it.first.run.row }, { it.first.run.x0 }, { it.first.x })).map { it.second }
        return Classification(ordered, used, margin.map { it.id }, notes)
    }

    /**
     * Note ops (contract 2.2.0) for the [margin] strokes: clusters of strokes whose boxes lie within [NOTE_GAP_CELLS]
     * cells of each other, each with the arrows among [free] whose tail lies within [SNAP_RADIUS] of the cluster (an
     * arrow touching several clusters joins them), so the note and its arrow are consumed together. Top to bottom,
     * strokes in drawing order; the rect bounds all of a note's strokes.
     */
    private fun notes(
        margin: List<Stroke>,
        free: List<Stroke>,
        geometry: NotebookGeometry,
        newId: () -> String,
    ): List<EditOp> {
        val gap = NOTE_GAP_CELLS * geometry.cell
        val clusters = ArrayList<Set<String>>()
        for (s in margin) {
            val b = bounds(s)
            val near = clusters.filter { c -> margin.any { it.id in c && rectGap(bounds(it), b) <= gap } }
            clusters.removeAll(near)
            clusters += near.flatten().toSet() + s.id
        }
        if (clusters.isEmpty()) return emptyList()
        val byId = free.associateBy { it.id }

        fun rect(ids: Set<String>) = ids.map { bounds(byId.getValue(it)) }.reduce(::unionOf)
        for (a in insertArrows(free)) {
            val home = clusters.firstOrNull { pointGap(rect(it), a.tail) <= SNAP_RADIUS } ?: continue
            val joined = clusters.filter { it == home || a.strokeIds.any { id -> id in it } }
            clusters.removeAll(joined)
            clusters += joined.flatten().toSet() + a.strokeIds
        }
        return clusters
            .map { it to rect(it) }
            .sortedWith(compareBy({ it.second.t }, { it.second.l }))
            .map { (ids, r) ->
                val strokeIds = free.map { it.id }.filter { it in ids }
                EditOp(newId(), OpKind.NOTE, strokeIds = strokeIds, rect = listOf(r.l, r.t, r.r - r.l, r.b - r.t))
            }
    }

    /** Where an op sits for ordering: its line's run and its x. */
    private class Placed(
        val run: TextRun,
        val x: Float,
    )

    /** Inline at the nearest word boundary on a text band with words, else after the nearest run above the tip. */
    private fun resolveTip(
        tip: Vec,
        boxes: Map<TextRun, List<WordBox>>,
        geometry: NotebookGeometry,
    ): Pair<OpAt, TextRun>? {
        val row = geometry.rowAt(tip.y) ?: return null
        if (geometry.bandAt(tip.y) == NotebookGeometry.Band.TEXT) {
            val onRow = boxes.mapValues { (_, b) -> b.filter { it.row == row } }.filterValues { it.isNotEmpty() }
            val nearest = onRow.minByOrNull { (_, b) -> distance(tip.x, b.first().left, b.last().right) }
            if (nearest != null) {
                val (run, b) = nearest
                return OpAt(run.lineId, sourceOffset(run, WordBoxes.nearestBoundary(b, tip.x)), AtMode.INLINE) to run
            }
        }
        val above =
            boxes.keys
                .filter { it.row + it.wrapRows - 1 < row }
                .groupBy { it.row + it.wrapRows }
                .maxByOrNull { it.key }
                ?.value
                ?.minByOrNull { it.x0 } ?: return null
        return OpAt(above.lineId, above.source.length, AtMode.AFTER_LINE) to above
    }

    private fun strikes(
        strokes: List<Stroke>,
        boxes: Map<TextRun, List<WordBox>>,
        geometry: NotebookGeometry,
        newId: () -> String,
    ): List<Pair<Placed, EditOp>> {
        // Per run: the display ranges struck and their strokes, merged where they overlap.
        val hits = HashMap<TextRun, MutableList<Pair<IntRange, MutableList<Stroke>>>>()
        for (s in strokes) {
            val (run, range) = struckWords(s, boxes, geometry) ?: continue
            val list = hits.getOrPut(run) { ArrayList() }
            // A stroke may bridge several earlier ranges: fold in every one it overlaps.
            val overlapping = list.filter { (r, _) -> r.first <= range.last && range.first <= r.last }
            list.removeAll(overlapping)
            val first = (overlapping.map { it.first.first } + range.first).min()
            val last = (overlapping.map { it.first.last } + range.last).max()
            list += (first..last) to (overlapping.flatMap { it.second } + s).toMutableList()
        }
        return hits.flatMap { (run, list) ->
            list.map { (range, group) ->
                Placed(run, group.minOf { s -> s.points.minOf { it.x } }) to
                    EditOp(newId(), OpKind.DELETE, target = target(run, range), strokeIds = group.map { it.id })
            }
        }
    }

    private fun replaces(
        strokes: List<Stroke>,
        boxes: Map<TextRun, List<WordBox>>,
        geometry: NotebookGeometry,
        newId: () -> String,
    ): List<Pair<Placed, EditOp>> {
        val byRow =
            strokes
                .filter { s ->
                    val c = centre(s)
                    val height = s.points.maxOf { it.y } - s.points.minOf { it.y }
                    !s.tool.isShape && !geometry.inMargin(c.x) && height <= MAX_ROWS_SPANNED * geometry.rowPitch &&
                        geometry.bandAt(c.y) == NotebookGeometry.Band.ANNOTATION
                }.groupBy { checkNotNull(geometry.rowAt(centre(it).y)) }
        return byRow.flatMap { (row, list) ->
            groups(list, geometry.cell.toFloat()).mapNotNull { group ->
                val x0 = group.minOf { s -> s.points.minOf { it.x } }
                val x1 = group.maxOf { s -> s.points.maxOf { it.x } }
                val (run, range) = bestOverlap(boxes, row, x0, x1) ?: return@mapNotNull null
                Placed(run, x0) to EditOp(newId(), OpKind.REPLACE, target = target(run, range), strokeIds = group.map { it.id })
            }
        }
    }

    /** Strokes sorted by left edge, split where the gap to the reach so far exceeds [gap]. */
    private fun groups(
        strokes: List<Stroke>,
        gap: Float,
    ): List<List<Stroke>> {
        val out = ArrayList<MutableList<Stroke>>()
        var reach = Float.NEGATIVE_INFINITY
        for (s in strokes.sortedBy { st -> st.points.minOf { it.x } }) {
            val left = s.points.minOf { it.x }
            if (out.isEmpty() || left - reach > gap) {
                out += ArrayList<Stroke>()
                reach = Float.NEGATIVE_INFINITY
            }
            out.last() += s
            reach = maxOf(reach, s.points.maxOf { it.x })
        }
        return out
    }

    /** The op target of display range [range] of [run]: source offsets and the source text there. */
    private fun target(
        run: TextRun,
        range: IntRange,
    ): OpTarget {
        val from = run.sourceMap.toSource(range.first)
        val to = run.sourceMap.toSource(range.last) + 1
        return OpTarget(run.lineId, from, to, run.source.substring(from, to))
    }

    /**
     * The source offset of display boundary [boundary]: the first char's offset at the start, else the offset of the
     * char at the boundary (past the closing markup of the word before it), or the end of the source.
     */
    private fun sourceOffset(
        run: TextRun,
        boundary: Int,
    ): Int =
        when {
            boundary <= 0 -> run.sourceMap.toSource(0)
            boundary >= run.display.length -> run.source.length
            else -> run.sourceMap.toSource(boundary)
        }

    private fun distance(
        x: Float,
        left: Float,
        right: Float,
    ): Float =
        when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }

    private companion object {
        /** A stroke spanning more rows than this is drawing, as in [Segmenter]. */
        const val MAX_ROWS_SPANNED = 1.5f

        /** Margin strokes this many grid cells apart or closer are one note. */
        const val NOTE_GAP_CELLS = 1f
    }
}

/** Bounding box of a stroke's points. */
private fun bounds(s: Stroke): RectF =
    RectF(s.points.minOf { it.x }, s.points.minOf { it.y }, s.points.maxOf { it.x }, s.points.maxOf { it.y })

private fun unionOf(
    a: RectF,
    b: RectF,
): RectF = RectF(minOf(a.l, b.l), minOf(a.t, b.t), maxOf(a.r, b.r), maxOf(a.b, b.b))

/** Distance between two boxes, 0 when they touch or overlap. */
private fun rectGap(
    a: RectF,
    b: RectF,
): Float = hypot(maxOf(0f, a.l - b.r, b.l - a.r), maxOf(0f, a.t - b.b, b.t - a.b))

/** Distance from [p] to box [r], 0 inside it. */
private fun pointGap(
    r: RectF,
    p: Vec,
): Float = hypot(maxOf(0f, r.l - p.x, p.x - r.r), maxOf(0f, r.t - p.y, p.y - r.b))

/** Centre of a stroke's bounding box. */
internal fun centre(s: Stroke): Vec =
    Vec((s.points.minOf { it.x } + s.points.maxOf { it.x }) / 2f, (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f)

/** The run and the words [s] strikes through ([StrikeDetector] over the text band of its row), or null. */
internal fun struckWords(
    s: Stroke,
    boxes: Map<TextRun, List<WordBox>>,
    geometry: NotebookGeometry,
): Pair<TextRun, IntRange>? {
    val row = geometry.rowAt(centre(s).y) ?: return null
    val band = geometry.textBandTop(row).toFloat()..geometry.rowLineY(row).toFloat()
    if (!StrikeDetector.isStrike(s, band)) return null
    return bestOverlap(boxes, row, s.points.minOf { it.x }, s.points.maxOf { it.x })
}

/** The run on [row] whose words overlap x [x0]..[x1] the most (by covered word width), and those words. */
internal fun bestOverlap(
    boxes: Map<TextRun, List<WordBox>>,
    row: Int,
    x0: Float,
    x1: Float,
): Pair<TextRun, IntRange>? =
    boxes
        .mapNotNull { (run, b) ->
            val onRow = b.filter { it.row == row }
            WordBoxes.overlapped(onRow, x0, x1)?.let { range ->
                val covered = onRow.filter { it.from >= range.first && it.to <= range.last + 1 }.sumOf { (it.right - it.left).toDouble() }
                Triple(run, range, covered)
            }
        }.maxByOrNull { it.third }
        ?.let { (run, range) -> run to range }
