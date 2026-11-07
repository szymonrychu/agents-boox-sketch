package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.Command
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.UndoStack
import io.github.szymonrychu.booxsketch.model.centreY
import io.github.szymonrychu.booxsketch.model.shiftStrokes
import kotlin.math.floor

/** The notebook after a spec version was placed, and per page index the rows whose pixels changed. */
data class Placement(
    val notebook: Notebook,
    val changedRows: Map<Int, IntRange>,
)

/**
 * Places the lines of a spec version on the notebook rows (spec 3.3, Q16 A). For every line in source order: a line
 * with a run keeps it (text, version and source line updated, refitted); a line from pending segments gets a new run
 * at the first segment's page, row and x0 and its strokes are consumed; any other line (origin null, OQ6 A; a kept
 * line with no run; an op line) gets a new row right after the previous line's run. Runs whose line is gone are
 * removed first; on a revert their strokes become visible ink again (OQ2 A). Lines inside fenced blocks, table rows
 * and blank lines are never placed: their segments' strokes stay ink.
 *
 * Fit: at scale 1 when it fits the column (x0 to the margin, or to the next run or segment on the row), else shrunk
 * down to [MIN_SCALE], else wrapped at word boundaries at [MIN_SCALE]. Rows a line needs that hold other runs, ink or
 * pending segments move down by whole rows with everything below them (on the selected page through [UndoStack] as
 * one [Command.ShiftRows]). Rows pushed past the last row move to a new page inserted right after (OQ4 A).
 */
object RunPlacer {
    const val MIN_SCALE = 0.85f

    /** Float rounding slack of a fit test, in page px. */
    private const val FIT_SLACK = 0.5f

    @Suppress("LongParameterList")
    fun apply(
        notebook: Notebook,
        version: Int,
        markdown: String,
        layout: SpecLayoutModel,
        measure: TextMeasure,
        geometry: NotebookGeometry,
        undo: UndoStack? = null,
    ): Placement {
        val source = markdown.split('\n').map { it.removeSuffix("\r") }
        val placeable = placeableLines(source)
        val work = Work(notebook, geometry, measure, undo)
        val shown =
            layout.lines
                .filter { placeable.getOrElse(it.sourceLine - 1) { false } }
                .map { it.lineId }
                .toSet()
        work.dropRuns(shown, unconsume = layout.revertedFrom != null)
        work.applyOps(layout.ops)
        val answered = work.answeredClients(layout.lines)
        var prev: String? = null
        for (line in layout.lines.sortedBy { it.sourceLine }) {
            val segments = (line.origin as? LineOrigin.Segments)?.ids.orEmpty()
            if (line.lineId !in shown) {
                work.dropSegments(answered, segments)
                continue
            }
            val stripped = SourceMap.stripWithColours(source[line.sourceLine - 1])
            val text = Text(stripped, HEADING.containsMatchIn(source[line.sourceLine - 1]), version, line.sourceLine)
            when {
                work.hasRun(line.lineId) -> work.update(line.lineId, text)
                work.hasSegments(answered, segments) -> work.placeAtSegments(line.lineId, text, answered, segments)
                else -> work.placeAfter(line.lineId, text, prev)
            }
            prev = line.lineId
        }
        work.dropAnswered(answered)
        return Placement(work.n.copy(appliedSpecVersion = version), work.dirty.toMap())
    }

    /** Display offset ranges of the rows [display] wraps into at [scale] within [column] px, breaking at spaces. */
    fun wrap(
        display: String,
        scale: Float,
        column: Float,
        measure: TextMeasure,
    ): List<IntRange> {
        val rows = ArrayList<IntRange>()
        var start = 0
        while (start < display.length) {
            while (start < display.length && display[start] == ' ') start++
            if (start >= display.length) break
            var end = start + 1
            var lastBreak = -1
            while (end < display.length && measure.width(display.substring(start, end + 1), scale) <= column) {
                if (display[end] == ' ') lastBreak = end
                end++
            }
            if (end < display.length && lastBreak > start && display[end] != ' ') end = lastBreak
            rows += start until end
            start = end
        }
        return rows.ifEmpty { listOf(0 until display.length) }
    }

    /** Scale and row count of [display] in a [column] px wide column. */
    fun fit(
        display: String,
        column: Float,
        measure: TextMeasure,
    ): Pair<Float, Int> {
        val full = measure.width(display, 1f)
        if (full <= column + FIT_SLACK) return 1f to 1
        val scale = maxOf(MIN_SCALE, column / full)
        if (measure.width(display, scale) <= column + FIT_SLACK) return scale to 1
        return MIN_SCALE to wrap(display, MIN_SCALE, column, measure).size
    }

    private val HEADING = Regex("^\\s*#{1,6}\\s")
    private val FENCE = Regex("^\\s*(```|~~~)")

    /** False for fence lines and the lines between them, table rows and blank lines. */
    private fun placeableLines(source: List<String>): List<Boolean> {
        var fenced = false
        return source.map { line ->
            when {
                FENCE.containsMatchIn(line) -> {
                    fenced = !fenced
                    false
                }

                fenced -> {
                    false
                }

                else -> {
                    line.isNotBlank() && !line.trimStart().startsWith("|")
                }
            }
        }
    }

    private class Text(
        val stripped: StrippedLine,
        val heading: Boolean,
        val version: Int,
        val sourceLine: Int,
    )

    /** The mutable placement state: the notebook so far and the dirty rows per page. */
    @Suppress("TooManyFunctions") // one step per placement rule
    private class Work(
        var n: Notebook,
        val g: NotebookGeometry,
        val measure: TextMeasure,
        val undo: UndoStack?,
    ) {
        val dirty = sortedMapOf<Int, IntRange>()

        /** Overflow page created for a page in this placement, so a second overflow reuses it. */
        private val overflowOf = HashMap<Int, Int>()

        fun page(i: Int) = n.pages[i]

        private fun setPage(
            i: Int,
            p: Page,
        ) {
            n = n.copy(pages = n.pages.toMutableList().also { it[i] = p })
        }

        private fun markDirty(
            page: Int,
            rows: IntRange,
        ) {
            if (rows.isEmpty()) return
            val old = dirty[page]
            val first = maxOf(0, minOf(rows.first, old?.first ?: rows.first))
            val last = minOf(g.rowCount - 1, maxOf(rows.last, old?.last ?: rows.last))
            dirty[page] = first..last
        }

        fun findRun(lineId: String): Pair<Int, TextRun>? {
            for ((i, p) in n.pages.withIndex()) p.text.firstOrNull { it.lineId == lineId }?.let { return i to it }
            return null
        }

        fun hasRun(lineId: String) = findRun(lineId) != null

        fun dropRuns(
            keep: Set<String>,
            unconsume: Boolean,
        ) {
            for (i in n.pages.indices) {
                val p = page(i)
                val gone = p.text.filter { it.lineId !in keep }
                if (gone.isEmpty()) continue
                gone.forEach { markDirty(i, it.row until it.row + it.wrapRows) }
                val restored = if (unconsume) gone.flatMap { it.sourceStrokeIds }.toSet() else emptySet()
                setPage(i, p.copy(text = p.text - gone.toSet(), consumed = p.consumed - restored))
                if (restored.isNotEmpty()) markDirty(i, 0 until g.rowCount)
            }
        }

        /** Reported ops leave the pending list; an applied op's strokes are consumed, a rejected op keeps its ink. */
        fun applyOps(ops: List<OpStatusModel>) {
            if (ops.isEmpty()) return
            val byId = ops.associateBy { it.id }
            for (i in n.pages.indices) {
                val p = page(i)
                val done = p.pendingOps.filter { it.op.id in byId }
                if (done.isEmpty()) continue
                val used = done.filter { byId.getValue(it.op.id).applied }.flatMap { it.op.strokeIds }
                setPage(i, p.copy(pendingOps = p.pendingOps - done.toSet(), consumed = p.consumed + used))
                markDirty(i, 0 until g.rowCount)
            }
        }

        /** The upload client ids this version answers: per segment id the client holding most of the version's segments. */
        fun answeredClients(lines: List<SpecLineModel>): Set<String> {
            val ids = lines.flatMap { (it.origin as? LineOrigin.Segments)?.ids.orEmpty() }.toSet()
            if (ids.isEmpty()) return emptySet()
            val all = n.pages.flatMap { it.pendingSegments }
            val counts = all.filter { it.segmentId in ids }.groupingBy { it.clientId }.eachCount()
            val best = counts.values.maxOrNull() ?: return emptySet()
            // Ties go to the oldest upload: the first one in page order.
            return setOfNotNull(all.firstOrNull { counts[it.clientId] == best }?.clientId)
        }

        private fun segmentsOf(
            answered: Set<String>,
            ids: List<String>,
        ): List<Pair<Int, PendingSegment>> =
            n.pages.withIndex().flatMap { (i, p) ->
                p.pendingSegments.filter { it.clientId in answered && it.segmentId in ids }.map { i to it }
            }

        fun hasSegments(
            answered: Set<String>,
            ids: List<String>,
        ) = ids.isNotEmpty() && segmentsOf(answered, ids).isNotEmpty()

        fun dropSegments(
            answered: Set<String>,
            ids: List<String>,
        ) {
            for ((i, s) in segmentsOf(answered, ids)) {
                val p = page(i)
                setPage(i, p.copy(pendingSegments = p.pendingSegments - s))
                markDirty(i, s.row..s.row)
            }
        }

        /** Every remaining pending segment of an answered upload: the version did not use it, its strokes stay ink. */
        fun dropAnswered(answered: Set<String>) {
            for (i in n.pages.indices) {
                val p = page(i)
                val left = p.pendingSegments.filter { it.clientId in answered }
                if (left.isEmpty()) continue
                setPage(i, p.copy(pendingSegments = p.pendingSegments - left.toSet()))
                left.forEach { markDirty(i, it.row..it.row) }
            }
        }

        private fun newRun(
            lineId: String,
            text: Text,
            row: Int,
            x0: Float,
            strokes: List<String>,
        ) = TextRun(
            lineId = lineId,
            specVersion = text.version,
            sourceLine = text.sourceLine,
            row = row,
            x0 = x0,
            display = text.stripped.display,
            colourSpans = text.stripped.spans,
            sourceMap = text.stripped.map,
            sourceStrokeIds = strokes,
            heading = text.heading,
        )

        fun update(
            lineId: String,
            text: Text,
        ) {
            val (i, old) = checkNotNull(findRun(lineId))
            val run =
                old.copy(
                    specVersion = text.version,
                    sourceLine = text.sourceLine,
                    display = text.stripped.display,
                    colourSpans = text.stripped.spans,
                    sourceMap = text.stripped.map,
                    heading = text.heading,
                )
            val changed = run.display != old.display || run.colourSpans != old.colourSpans || run.heading != old.heading
            put(i, run, replacing = old, dirty = changed)
        }

        fun placeAtSegments(
            lineId: String,
            text: Text,
            answered: Set<String>,
            ids: List<String>,
        ) {
            val found = segmentsOf(answered, ids)
            val (i, first) = found.minWith(compareBy({ it.first }, { it.second.row }, { it.second.x0 }))
            val mine = found.filter { it.first == i }.map { it.second }
            val strokes = mine.flatMap { it.strokeIds }
            val p = page(i)
            setPage(i, p.copy(pendingSegments = p.pendingSegments - mine.toSet(), consumed = p.consumed + strokes))
            put(i, newRun(lineId, text, first.row, first.x0, strokes), replacing = null, dirty = true)
        }

        fun placeAfter(
            lineId: String,
            text: Text,
            prev: String?,
        ) {
            val anchor = prev?.let(::findRun)
            val i = anchor?.first ?: 0
            val row = anchor?.second?.let { it.row + it.wrapRows } ?: 0
            put(i, newRun(lineId, text, row, g.textLeft.toFloat(), emptyList()), replacing = null, dirty = true, ownRowFree = false)
        }

        /**
         * Fits [run] on page [i] and makes room for the rows it needs: its own row too unless [ownRowFree] (a run in
         * place of its segment or its old run owns that row). Then stores it, replacing [replacing].
         */
        private fun put(
            i: Int,
            run: TextRun,
            replacing: TextRun?,
            dirty: Boolean,
            ownRowFree: Boolean = true,
        ) {
            val others = page(i).text.filter { it !== replacing }
            val right = rightBound(page(i), run, others)
            val (scale, rows) = fit(run.display, right - run.x0, measure)
            val placed = run.copy(scale = scale, wrapRows = rows)
            setPage(i, page(i).copy(text = others))
            val from = if (ownRowFree) placed.row + 1 else placed.row
            val needed = from until placed.row + rows
            val firstTaken = needed.firstOrNull { occupied(page(i), it) }
            if (firstTaken != null) shift(i, firstTaken, placed.row + rows - firstTaken)
            setPage(i, page(i).copy(text = page(i).text + placed))
            if (dirty || replacing?.wrapRows != rows) markDirty(i, placed.row until placed.row + maxOf(rows, replacing?.wrapRows ?: 0))
            // Only rows this placement pushed down (or a run placed past the foot) leave the page; ink the user wrote
            // in the foot stays where it is otherwise.
            if (firstTaken != null || placed.row + rows > g.rowCount) settleOverflow(i)
        }

        /** The column's right edge: the margin, or the next run or pending segment to the right on the same row. */
        private fun rightBound(
            p: Page,
            run: TextRun,
            others: List<TextRun>,
        ): Float {
            val next =
                others.filter { it.row == run.row && it.x0 > run.x0 }.map { it.x0 } +
                    p.pendingSegments.filter { it.row == run.row && it.x0 > run.x0 }.map { it.x0 }
            return minOf(g.marginX.toFloat(), next.minOrNull() ?: Float.MAX_VALUE)
        }

        private fun rowOf(y: Float): Int = floor((y - g.topMargin) / g.rowPitch).toInt()

        private fun occupied(
            p: Page,
            row: Int,
        ): Boolean =
            p.text.any { row in it.row until it.row + it.wrapRows } ||
                p.pendingSegments.any { it.row == row } ||
                p.visibleStrokes.any { it.points.isNotEmpty() && rowOf(centreY(it)) == row }

        /** Moves rows [from] and below of page [i] (runs, every stroke, pending segments) down by [rows]. */
        private fun shift(
            i: Int,
            from: Int,
            rows: Int,
        ) {
            val fromY = g.rowTop(from).toFloat()
            val dy = (rows * g.rowPitch).toFloat()
            val stack = undo
            if (stack != null && i == n.current) {
                n = stack.apply(n, Command.ShiftRows(fromY, dy))
            } else {
                setPage(i, page(i).copy(strokes = shiftStrokes(page(i).strokes, fromY, dy)))
            }
            val p = page(i)
            setPage(
                i,
                p.copy(
                    text = p.text.map { if (it.row >= from) it.copy(row = it.row + rows) else it },
                    pendingSegments = p.pendingSegments.map { if (it.row >= from) it.copy(row = it.row + rows) else it },
                ),
            )
            markDirty(i, from until g.rowCount)
        }

        /** Moves everything past the last row of page [i] to the overflow page right after it (OQ4 A), repeatedly. */
        private fun settleOverflow(start: Int) {
            var i = start
            while (true) {
                val p = page(i)
                var cut = g.rowCount
                // A run that does not end on this page moves whole, with everything below its first row.
                while (true) {
                    val straddling = p.text.filter { it.row < cut && it.row + it.wrapRows > cut }.minOfOrNull { it.row } ?: break
                    cut = straddling
                }
                val runs = p.text.filter { it.row >= cut }
                val strokes = p.strokes.filter { it.points.isNotEmpty() && rowOf(centreY(it)) >= cut }
                val segments = p.pendingSegments.filter { it.row >= cut }
                if (runs.isEmpty() && strokes.isEmpty() && segments.isEmpty()) return
                val ids = strokes.map { it.id }.toSet()
                setPage(
                    i,
                    p.copy(
                        text = p.text - runs.toSet(),
                        strokes = p.strokes.filter { it.id !in ids },
                        pendingSegments = p.pendingSegments - segments.toSet(),
                        consumed = p.consumed - ids,
                    ),
                )
                val target = overflowOf[i] ?: insertAfter(i)
                val dy = -(cut * g.rowPitch).toFloat()
                val t = page(target)
                setPage(
                    target,
                    t.copy(
                        text = t.text + runs.map { it.copy(row = it.row - cut) },
                        strokes = t.strokes + shiftStrokes(strokes, Float.NEGATIVE_INFINITY, dy),
                        pendingSegments = t.pendingSegments + segments.map { it.copy(row = it.row - cut) },
                        consumed = t.consumed + p.consumed.intersect(ids),
                        changedSinceSend = t.changedSinceSend || strokes.any { it.id !in p.consumed },
                    ),
                )
                markDirty(target, 0 until g.rowCount)
                i = target
            }
        }

        private fun insertAfter(i: Int): Int {
            val at = i + 1
            val empty = Page(emptyList(), false)
            n = undo?.insertPage(n, at, empty) ?: n.insertPage(at, empty)
            val moved = dirty.entries.associate { (k, v) -> (if (k >= at) k + 1 else k) to v }
            dirty.clear()
            dirty.putAll(moved)
            val remapped = overflowOf.entries.associate { (k, v) -> (if (k >= at) k + 1 else k) to (if (v >= at) v + 1 else v) }
            overflowOf.clear()
            overflowOf.putAll(remapped)
            overflowOf[i] = at
            return at
        }
    }
}
