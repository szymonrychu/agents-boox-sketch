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

/**
 * Insert marks drawn with the shape tools (Q12 B): a [Tool.RECTANGLE] lying in the margin (at least 80 percent of its
 * width right of the margin line) with pen strokes centred inside it, and an [Tool.ARROW] whose tail lies within
 * [SNAP_RADIUS] of one of its anchors and whose tip is in the text column on a row. One arrow per box: the first one
 * drawn.
 */
object ToolInsertMarks : InsertMarkFinder {
    private const val IN_MARGIN = 0.8f

    override fun find(
        page: Page,
        candidates: List<Stroke>,
        geometry: NotebookGeometry,
    ): List<InsertMark> {
        val arrows = candidates.filter { it.tool == Tool.ARROW && it.points.size >= 2 }
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
                        val tail = a.points.first()
                        val tip = a.points.last()
                        a.id !in used && anchors.any { hypot(it.x - tail.x, it.y - tail.y) <= SNAP_RADIUS } &&
                            geometry.inTextColumn(tip.x) && geometry.rowAt(tip.y) != null
                    } ?: return@mapNotNull null
                val notes = pens.filter { s -> centre(s).let { it.x in r.l..r.r && it.y in r.t..r.b } }
                if (notes.isEmpty()) return@mapNotNull null
                used += arrow.id
                InsertMark(box.id, listOf(arrow.id), r, arrow.points.last().let { Vec(it.x, it.y) }, notes.map { it.id })
            }
    }
}

/** The edit ops of a page, the strokes they use, and the margin strokes that are no op (sent as plain ink, OQ3 A). */
data class Classification(
    val ops: List<EditOp>,
    val opStrokeIds: Set<String>,
    val marginInk: List<String>,
)

/**
 * Classifies the handwriting of a notebook page with text runs into edit ops (spec 4.3, rules 2-4). Candidates are the
 * visible strokes not in a pending segment or op. In order: margin inserts ([finders]; the arrow tip on a text band
 * inserts inline at the nearest word boundary, anywhere else after the nearest run above), strikes ([StrikeDetector]
 * over a text band crossing words: delete those words; strikes over the same words of a run are one op), replaces
 * (pen strokes centred in an annotation band, grouped by x with a gap over one cell, over the words of a run on that
 * row: replace those words). Targets are whole words, as source offsets with the source text as quote. The ops come
 * ordered by their line (row, then x0) and by x within a line (Q13 A). Everything else is new handwriting for the
 * [Segmenter]; margin strokes outside any insert are [Classification.marginInk].
 */
class AnnotationClassifier(
    private val finders: List<InsertMarkFinder>,
) {
    fun classify(
        page: Page,
        geometry: NotebookGeometry,
        measure: TextMeasure,
        newId: () -> String,
    ): Classification {
        val pending = page.pendingSegments.flatMap { it.strokeIds }.toSet() + page.pendingOps.flatMap { it.op.strokeIds }
        val candidates = page.visibleStrokes.filter { it.points.isNotEmpty() && it.id !in pending && it.id !in page.rejectedStrokes }
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
            ops += strikes(candidates.filter { it.id !in used }, boxes, geometry, newId).onEach { used += it.second.strokeIds }
            ops += replaces(candidates.filter { it.id !in used }, boxes, geometry, newId).onEach { used += it.second.strokeIds }
        }
        val ordered = ops.sortedWith(compareBy({ it.first.run.row }, { it.first.run.x0 }, { it.first.x })).map { it.second }
        val margin = candidates.filter { it.id !in used && geometry.inMargin(centre(it).x) }.map { it.id }
        return Classification(ordered, used, margin)
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
                .maxWithOrNull(compareBy({ it.row + it.wrapRows }, { it.x0 })) ?: return null
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

    /** The source offset of display boundary [boundary]: right after the char before it, or the first char's offset. */
    private fun sourceOffset(
        run: TextRun,
        boundary: Int,
    ): Int = if (boundary <= 0) run.sourceMap.toSource(0) else run.sourceMap.toSource(boundary - 1) + 1

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
    }
}

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
