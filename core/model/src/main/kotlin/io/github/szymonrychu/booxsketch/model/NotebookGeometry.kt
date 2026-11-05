package io.github.szymonrychu.booxsketch.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The notebook page: rows, bands and margin in page-canvas pixels, the unit strokes are stored in
 * (the 1404 x 1872 board page is 6 mm per [cell] on glass in portrait). Every page is a notebook page.
 *
 * Row `i` (0-based) spans [rowTop] to [rowLineY]; the line closes the row. The upper half is the
 * annotation band, the lower half the text band. A top margin of one row stays empty (page title
 * space) and the rest of the page holds [rowCount] rows. The right 30 percent is the margin: from
 * [marginX] on, and rows have no line there. The text column runs from [textLeft] to [marginX].
 */
class NotebookGeometry(
    val pageW: Int,
    val pageH: Int,
) {
    val cell: Int = CELL
    val rowPitch: Int = ROW_PITCH
    val topMargin: Int = ROW_PITCH
    val rowCount: Int = ((pageH - topMargin) / rowPitch).coerceAtLeast(0)
    val textLeft: Int = CELL
    val marginX: Int = (pageW * (1f - MARGIN_FRACTION)).roundToInt()

    /** The two halves of a row. */
    enum class Band { ANNOTATION, TEXT }

    fun rowTop(row: Int): Int = topMargin + row * rowPitch

    /** Y of the line that closes [row]; text sits just above it. */
    fun rowLineY(row: Int): Int = rowTop(row) + rowPitch

    fun annotationBandTop(row: Int): Int = rowTop(row)

    fun textBandTop(row: Int): Int = rowTop(row) + cell

    /** The row containing page y [y], or null in the top margin, the foot or off the page. */
    fun rowAt(y: Float): Int? {
        if (y < topMargin) return null
        val row = floor((y - topMargin) / rowPitch).toInt()
        return row.takeIf { it < rowCount }
    }

    /** The band containing page y [y], or null outside the rows. */
    fun bandAt(y: Float): Band? {
        val row = rowAt(y) ?: return null
        return if (y < textBandTop(row)) Band.ANNOTATION else Band.TEXT
    }

    fun inMargin(x: Float): Boolean = x >= marginX

    fun inTextColumn(x: Float): Boolean = x >= textLeft && x < marginX

    /** Indices of the rows whose line lies in page y [from]..[to] (inclusive). */
    fun rowLinesIn(
        from: Float,
        to: Float,
    ): IntRange {
        val first = ceil((from - topMargin) / rowPitch).toInt() - 1
        val last = floor((to - topMargin) / rowPitch).toInt() - 1
        return first.coerceAtLeast(0)..last.coerceAtMost(rowCount - 1)
    }

    /** Dot column indices (dot x = index * [cell]) with x in [from]..[to] inside the page; column 0 is the page edge. */
    fun dotColumnsIn(
        from: Float,
        to: Float,
    ): IntRange = dotRange(from, to, pageW)

    /** Dot row indices (dot y = index * [cell]) with y in [from]..[to] inside the page. */
    fun dotRowsIn(
        from: Float,
        to: Float,
    ): IntRange = dotRange(from, to, pageH)

    private fun dotRange(
        from: Float,
        to: Float,
        extent: Int,
    ): IntRange {
        val first = ceil(from / cell).toInt().coerceAtLeast(1)
        val last = floor(to / cell).toInt().coerceAtMost((extent - 1) / cell)
        return first..last
    }

    /** True when the dot at cell intersection ([column], [row]) lies on a row line of the text column, which draws over it. */
    fun dotCovered(
        column: Int,
        row: Int,
    ): Boolean {
        val x = column * cell
        val y = row * cell
        if (x < textLeft || x >= marginX) return false
        val k = (y - topMargin) / rowPitch - 1
        return y >= topMargin + rowPitch && (y - topMargin) % rowPitch == 0 && k in 0 until rowCount
    }

    companion object {
        /** 6 mm on glass in portrait. */
        const val CELL = 53

        /** 12 mm: two cells. */
        const val ROW_PITCH = 2 * CELL

        const val MARGIN_FRACTION = 0.30f
    }
}
