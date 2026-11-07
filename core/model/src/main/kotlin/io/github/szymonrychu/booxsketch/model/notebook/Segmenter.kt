package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke

/** One run of new handwriting on a single row, ready to be uploaded as a layout segment. */
data class SegmentDraft(
    val id: String,
    val row: Int,
    val x0: Float,
    val x1: Float,
    val strokeIds: List<String>,
)

/** [segments] are the row lines, [drawing] the ids of strokes sent as plain drawing ink. */
data class Segmentation(
    val segments: List<SegmentDraft>,
    val drawing: List<String>,
)

/**
 * Splits the new handwriting of a notebook page into row lines (spec 4.2). A candidate is a visible stroke that is not
 * in `exclude` (ops and regions), not already in a pending segment and whose centre is not in the margin. Shape
 * strokes, strokes spanning more than 1.5 rows and strokes centred in the top margin or below the last row are
 * drawing. The others are grouped by the row of their vertical centre and split where the gap between neighbouring
 * strokes exceeds two cells.
 */
object Segmenter {
    private const val MAX_ROWS_SPANNED = 1.5f
    private const val GAP_CELLS = 2

    fun segment(
        page: Page,
        geometry: NotebookGeometry,
        pageIndex: Int,
        exclude: Set<String> = emptySet(),
    ): Segmentation {
        val skip = exclude + page.pendingSegments.flatMap { it.strokeIds }
        val drawing = ArrayList<String>()
        val byRow = sortedMapOf<Int, MutableList<Box>>()
        val candidates = page.visibleStrokes.filter { it.id !in skip && it.points.isNotEmpty() }.map(::Box)
        for (box in candidates.filterNot { geometry.inMargin(it.centreX) }) {
            val row = geometry.rowAt(box.centreY)
            if (box.isShape || row == null || box.height > MAX_ROWS_SPANNED * geometry.rowPitch) {
                drawing += box.id
            } else {
                byRow.getOrPut(row) { ArrayList() } += box
            }
        }
        val segments = byRow.flatMap { (row, boxes) -> split(boxes, row, pageIndex, GAP_CELLS * geometry.cell) }
        return Segmentation(segments, drawing)
    }

    private fun split(
        boxes: List<Box>,
        row: Int,
        pageIndex: Int,
        maxGap: Int,
    ): List<SegmentDraft> {
        val groups = ArrayList<MutableList<Box>>()
        var reach = Float.NEGATIVE_INFINITY
        for (box in boxes.sortedBy { it.minX }) {
            if (groups.isEmpty() || box.minX - reach > maxGap) groups += ArrayList<Box>()
            groups.last() += box
            reach = if (groups.last().size == 1) box.maxX else maxOf(reach, box.maxX)
        }
        return groups.mapIndexed { n, g ->
            SegmentDraft("p$pageIndex-r$row-$n", row, g.minOf { it.minX }, g.maxOf { it.maxX }, g.map { it.id })
        }
    }

    private class Box(
        stroke: Stroke,
    ) {
        val id = stroke.id
        val isShape = stroke.tool.isShape
        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        private val minY = stroke.points.minOf { it.y }
        private val maxY = stroke.points.maxOf { it.y }
        val centreX = (minX + maxX) / 2f
        val centreY = (minY + maxY) / 2f
        val height = maxY - minY
    }
}
