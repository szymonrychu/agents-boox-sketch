package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.UndoStack
import io.github.szymonrychu.booxsketch.model.centreY
import io.github.szymonrychu.booxsketch.model.shiftCentres
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
 * line with no run; an op line) gets a new row right after the previous line's run. Every kept run is laid out and
 * marked changed again, so the whole page text is rendered anew on every version. Runs whose line is gone are
 * removed first; on a revert their strokes become visible ink again (OQ2 A, the page counts as changed so Send picks
 * the ink up) and the old version's segment origins are ignored (segment ids repeat across uploads). Lines inside
 * fenced blocks, table rows and blank lines are never placed: their segments' strokes stay ink. A first shown line
 * with no anchor goes just above the next shown line's run or segment.
 *
 * Fit: every run at the one [TEXT_SCALE], wrapped at word boundaries when it is wider than the column (x0 to the
 * margin, or to the next run or segment on the row). A column narrower than [MIN_COLUMN_CELLS] cells
 * is widened to that, moving x0 left when the margin is closer. Rows a line needs that hold other runs, ink or pending
 * segments move down by whole rows with everything below them; the ink moves through [UndoStack.shiftRows], which is
 * not an undo step and keeps the history aligned. An image region and its ink move (or overflow) as one unit. Rows
 * pushed past the last row move to a new page inserted right after (OQ4 A); a later overflow of the same page goes
 * above the earlier one there.
 */
object RunPlacer {
    /** The one text scale of every run: lines never shrink to fit, so all text on the page has the same size. */
    const val TEXT_SCALE = 1f

    /** The narrowest column a run is fitted in, in grid cells (about 15 characters at scale 1). */
    const val MIN_COLUMN_CELLS = 6

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
        answeredUploads: Set<String>? = null,
    ): Placement {
        val source = markdown.split('\n').map { it.removeSuffix("\r") }
        val work = Work(notebook, geometry, measure, undo)
        val revert = layout.revertedFrom != null
        val diagrams = DiagramBlocks.find(source, layout.lines, revert, work::hasRun)
        val placeable = placeableLines(source, diagrams.keys)
        val shown =
            layout.lines
                .filter { placeable.getOrElse(it.sourceLine - 1) { false } }
                .map { it.lineId }
                .toSet()
        work.dropRuns(shown, unconsume = revert)
        layout.revertedFrom?.let { work.restoreOpStrokes(it) }
        val referenced = ImageRegions.referencedIds(markdown).toSet()
        work.applyOps(layout.ops, version)
        work.applyPictures(referenced, answeredUploads)
        work.dropUnreferencedRegions(referenced)

        // A revert copies the old version's origins: its segment ids may match new, unanswered handwriting.
        val bySourceLine = layout.lines.associateBy { it.sourceLine }

        fun ownSegments(line: SpecLineModel) = (line.origin as? LineOrigin.Segments)?.ids.orEmpty()

        // A diagram's opening fence also takes the segments of the other lines of its block (the labels).
        fun segmentsOf(line: SpecLineModel): List<String> {
            val block = diagrams[line.sourceLine]
            return when {
                revert -> emptyList()
                block != null -> block.lines.flatMap { bySourceLine[it]?.let(::ownSegments).orEmpty() }
                else -> ownSegments(line)
            }
        }
        val ordered = layout.lines.sortedBy { it.sourceLine }
        val answered = work.answeredClients(ordered.flatMap(::segmentsOf))
        var prev: String? = null
        for ((k, line) in ordered.withIndex()) {
            val segments = segmentsOf(line)
            if (line.lineId !in shown) {
                work.dropSegments(answered, segments)
                continue
            }
            val diagram = diagrams[line.sourceLine]
            val text = textOf(source[line.sourceLine - 1], diagram?.marker, version, line.sourceLine)
            when {
                work.hasRun(line.lineId) -> work.update(line.lineId, text)
                work.hasSegments(answered, segments) -> work.placeAtSegments(line.lineId, text, answered, segments, diagram != null)
                prev != null -> work.placeAfter(line.lineId, text, prev)
                else -> work.placeFirst(line.lineId, text, ordered.drop(k + 1).filter { it.lineId in shown }, answered, ::segmentsOf)
            }
            prev = line.lineId
        }
        work.dropAnswered(answered)
        work.release(version, answeredUploads)
        return Placement(work.n.copy(appliedSpecVersion = version), work.dirty.toMap())
    }

    /** The run text of a source line, or of a diagram's [marker] (plain, no markup) when it is not null. */
    private fun textOf(
        line: String,
        marker: String?,
        version: Int,
        sourceLine: Int,
    ): Text =
        if (marker != null) {
            Text(marker, StrippedLine(marker, SourceMap(IntArray(marker.length + 1) { it }), emptyList()), false, version, sourceLine)
        } else {
            Text(line, SourceMap.stripWithColours(line), HEADING.containsMatchIn(line), version, sourceLine)
        }

    /** True when a saved run was laid out at another scale than [TEXT_SCALE] (an older build shrank it to fit). */
    fun needsRelayout(notebook: Notebook): Boolean = notebook.pages.any { p -> p.text.any { it.scale != TEXT_SCALE } }

    /**
     * Lays every run whose scale is not [TEXT_SCALE] out again at [TEXT_SCALE] with the wrap, column and row-shift
     * rules of [apply] (the same placement step), for notebooks saved by older builds. Text, strokes, consumed ink,
     * source strokes, undo-free row shifts and the applied spec version stay as they were.
     */
    fun relayout(
        notebook: Notebook,
        measure: TextMeasure,
        geometry: NotebookGeometry,
    ): Placement {
        val work = Work(notebook, geometry, measure, null)
        val stale = notebook.pages.flatMap { p -> p.text.filter { it.scale != TEXT_SCALE }.map { it.lineId } }
        for (id in stale) work.relayoutRun(id)
        return Placement(work.n, work.dirty.toMap())
    }

    /**
     * Releases the pending segments and ops of uploads based on a version older than [version] (all of them when null),
     * limited to the uploads in [uploads] (client ids; all when null): their strokes become ordinary unsent ink again and
     * their pages count as changed. A pending picture whose region id is in [referenced] (the image ids the answering
     * version's markdown embeds) is applied first, so only the unreferenced ones are sent again.
     */
    fun releasePending(
        notebook: Notebook,
        version: Int?,
        geometry: NotebookGeometry,
        uploads: Set<String>?,
        referenced: Set<String>,
    ): Placement {
        val work = Work(notebook, geometry, TextMeasure { text, scale -> text.length * scale }, null)
        work.applyPictures(referenced, uploads)
        work.release(version, uploads)
        return Placement(work.n, work.dirty.toMap())
    }

    /** Display offset ranges of the rows [display] wraps into at [scale] within [column] px, breaking at spaces. */
    fun wrap(
        display: String,
        scale: Float,
        column: Float,
        measure: TextMeasure,
        bold: Boolean = false,
    ): List<IntRange> {
        val rows = ArrayList<IntRange>()
        var start = 0
        while (start < display.length) {
            while (start < display.length && display[start] == ' ') start++
            if (start >= display.length) break
            var end = start + 1
            var lastBreak = -1
            while (end < display.length && measure.width(display.substring(start, end + 1), scale, bold) <= column) {
                if (display[end] == ' ') lastBreak = end
                end++
            }
            if (end < display.length && lastBreak > start && display[end] != ' ') end = lastBreak
            rows += start until end
            start = end
        }
        return rows.ifEmpty { listOf(0 until display.length) }
    }

    /** Row count of [display] at [TEXT_SCALE] in a [column] px wide column. */
    fun fit(
        display: String,
        column: Float,
        measure: TextMeasure,
        bold: Boolean = false,
    ): Int {
        if (measure.width(display, TEXT_SCALE, bold) <= column + FIT_SLACK) return 1
        return wrap(display, TEXT_SCALE, column, measure, bold).size
    }

    private val HEADING = Regex("^\\s*#{1,6}\\s")
    private val FENCE = Regex("^\\s*(```|~~~)")

    /** A line that is only an image reference: on the board the image region itself stands for it. */
    private val IMAGE_LINE = Regex("^\\s*!\\[[^\\]]*]\\([^)]*\\)\\s*$")

    /**
     * False for fence lines (except the opening fence of a diagram block in [diagramOpens], 1-based source lines) and
     * the lines between them, table rows, image-only lines and blank lines.
     */
    private fun placeableLines(
        source: List<String>,
        diagramOpens: Set<Int>,
    ): List<Boolean> {
        var fenced = false
        return source.mapIndexed { index, line ->
            when {
                FENCE.containsMatchIn(line) -> {
                    val opening = !fenced && index + 1 in diagramOpens
                    fenced = !fenced
                    opening
                }

                fenced -> {
                    false
                }

                else -> {
                    line.isNotBlank() && !line.trimStart().startsWith("|") && !IMAGE_LINE.matches(line)
                }
            }
        }
    }

    private class Text(
        val source: String,
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
                val back = restored.intersect(p.consumed)
                setPage(
                    i,
                    p.copy(
                        text = p.text - gone.toSet(),
                        consumed = p.consumed - back,
                        changedSinceSend =
                            p.changedSinceSend || back.isNotEmpty(),
                    ),
                )
                if (back.isNotEmpty()) markDirty(i, 0 until g.rowCount)
            }
        }

        /**
         * A revert to version [target] undoes the edit ops applied by later versions: their strokes are visible ink
         * again and the page counts as changed, so Send re-offers them (the OQ2 A rule, keyed on the version).
         */
        fun restoreOpStrokes(target: Int) {
            for (i in n.pages.indices) {
                val p = page(i)
                val back = p.opConsumedAt.filterValues { it > target }.keys
                if (back.isEmpty()) continue
                setPage(i, p.copy(consumed = p.consumed - back, opConsumedAt = p.opConsumedAt - back, changedSinceSend = true))
                markDirty(i, 0 until g.rowCount)
            }
        }

        /**
         * Reported ops leave the pending list; a rejected op keeps its ink. An applied op's strokes are consumed (and
         * recorded as consumed by [version]), except an image op's: its ink is the picture and stays, its region stroke
         * becomes an applied region (spec 3.6).
         */
        fun applyOps(
            ops: List<OpStatusModel>,
            version: Int,
        ) {
            if (ops.isEmpty()) return
            val byId = ops.associateBy { it.id }
            for (i in n.pages.indices) {
                val p = page(i)
                val done = p.pendingOps.filter { it.op.id in byId }
                if (done.isEmpty()) continue
                val (applied, rejected) = done.partition { byId.getValue(it.op.id).applied }
                val (images, edits) = applied.partition { it.op.kind == OpKind.IMAGE }
                val regions = appliedRegionsOf(p, images)
                setPage(
                    i,
                    p.copy(
                        pendingOps = p.pendingOps - done.toSet(),
                        consumed = p.consumed + edits.flatMap { it.op.strokeIds },
                        opConsumedAt = p.opConsumedAt + edits.flatMap { it.op.strokeIds }.associateWith { version },
                        // A rejected image op returns its region to pending (sent again); its ink is not rejected ink.
                        rejectedStrokes = p.rejectedStrokes + rejected.filter { it.op.kind != OpKind.IMAGE }.flatMap { it.op.strokeIds },
                        appliedRegions = p.appliedRegions + regions,
                    ),
                )
                markDirty(i, 0 until g.rowCount)
            }
        }

        /** Region stroke id to region id of the IMAGE ops [images] of page [p]. */
        private fun appliedRegionsOf(
            p: Page,
            images: List<PendingOp>,
        ): List<Pair<String, String>> {
            val regionIds =
                p.strokes
                    .filter { it.tool == Tool.IMAGE_REGION }
                    .map { it.id }
                    .toSet()
            return images.flatMap { d ->
                val regionId = d.op.region?.id
                d.op.strokeIds
                    .filter { it in regionIds && regionId != null }
                    .map { it to regionId.orEmpty() }
            }
        }

        /**
         * A picture sent as page meta has no op status: the pending IMAGE op of an upload in [uploads] (all when null)
         * whose region id is in [referenced] leaves the pending list and its region is applied, as an applied image op.
         */
        fun applyPictures(
            referenced: Set<String>,
            uploads: Set<String>?,
        ) {
            for (i in n.pages.indices) {
                val p = page(i)
                val done =
                    p.pendingOps.filter {
                        it.op.kind == OpKind.IMAGE && it.op.region?.id in referenced && (uploads == null || it.clientId in uploads)
                    }
                if (done.isEmpty()) continue
                setPage(i, p.copy(pendingOps = p.pendingOps - done.toSet(), appliedRegions = p.appliedRegions + appliedRegionsOf(p, done)))
                markDirty(i, 0 until g.rowCount)
            }
        }

        /**
         * An applied region whose image the shown version does not reference is pending again (OQ2 A): its frame is
         * dashed, it is exported and sent as an image op once more, and the page counts as changed.
         */
        fun dropUnreferencedRegions(referenced: Set<String>) {
            for (i in n.pages.indices) {
                val p = page(i)
                val gone = p.appliedRegions.filterValues { it !in referenced }
                if (gone.isEmpty()) continue
                setPage(i, p.copy(appliedRegions = p.appliedRegions - gone.keys, changedSinceSend = true))
                markDirty(i, 0 until g.rowCount)
            }
        }

        /** The upload client ids this version answers: the client holding most of the version's segment [ids]. */
        fun answeredClients(segmentIds: List<String>): Set<String> {
            val ids = segmentIds.toSet()
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

        /** Pending marks of [uploads] (all when null) based on a version below [version] (any when null): their ink is unsent again. */
        fun release(
            version: Int?,
            uploads: Set<String>?,
        ) {
            for (i in n.pages.indices) {
                val p = page(i)
                val segments =
                    p.pendingSegments.filter {
                        (version == null || it.baseVersion < version) &&
                            (uploads == null || it.clientId in uploads)
                    }
                val ops =
                    p.pendingOps.filter {
                        (version == null || it.baseVersion < version) && (uploads == null || it.clientId in uploads)
                    }
                if (segments.isEmpty() && ops.isEmpty()) continue
                setPage(
                    i,
                    p.copy(
                        pendingSegments = p.pendingSegments - segments.toSet(),
                        pendingOps = p.pendingOps - ops.toSet(),
                        changedSinceSend = true,
                    ),
                )
                segments.forEach { markDirty(i, it.row..it.row) }
                if (ops.isNotEmpty()) markDirty(i, 0 until g.rowCount)
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
            source = text.source,
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
                    source = text.source,
                )
            // Changed or not, the run is rendered again with the rest of the page (one size, nothing reused).
            put(i, run, replacing = old)
        }

        /** The run [lineId] fitted again at [TEXT_SCALE] where it stands (its row stays); rows below move as needed. */
        fun relayoutRun(lineId: String) {
            val (i, run) = checkNotNull(findRun(lineId))
            put(i, run, replacing = run)
        }

        fun placeAtSegments(
            lineId: String,
            text: Text,
            answered: Set<String>,
            ids: List<String>,
            diagram: Boolean = false,
        ) {
            val found = segmentsOf(answered, ids)
            val (i, first) = found.minWith(compareBy({ it.first }, { it.second.row }, { it.second.x0 }))
            val mine = found.filter { it.first == i }.map { it.second }
            val p = page(i)
            val labels = mine.flatMap { it.strokeIds }
            val strokes = if (diagram) labels + DiagramBlocks.ink(p, labels) else labels
            setPage(i, p.copy(pendingSegments = p.pendingSegments - mine.toSet(), consumed = p.consumed + strokes))
            put(i, newRun(lineId, text, first.row, first.x0, strokes), replacing = null)
        }

        fun placeAfter(
            lineId: String,
            text: Text,
            prev: String,
        ) {
            val anchor = findRun(prev)
            val i = anchor?.first ?: 0
            val row = anchor?.second?.let { it.row + it.wrapRows } ?: 0
            put(i, newRun(lineId, text, row, g.textLeft.toFloat(), emptyList()), replacing = null, ownRowFree = false)
        }

        /**
         * A first shown line with nothing before it: above the first of [later] that has a run or (answered) segments,
         * on the free row right above it, else on its row pushing it down; page 0 row 0 when there is none.
         */
        fun placeFirst(
            lineId: String,
            text: Text,
            later: List<SpecLineModel>,
            answered: Set<String>,
            idsOf: (SpecLineModel) -> List<String>,
        ) {
            val anchor =
                later.firstNotNullOfOrNull { line ->
                    findRun(line.lineId)?.let { (i, r) -> i to r.row }
                        ?: segmentsOf(answered, idsOf(line))
                            .minWithOrNull(compareBy({ it.first }, { it.second.row }))
                            ?.let { (i, s) -> i to s.row }
                }
            val (i, next) = anchor ?: (0 to 0)
            // The free row right above the anchor when there is one, so nothing moves.
            val row = if (next > 0 && !occupied(page(i), next - 1)) next - 1 else next
            put(i, newRun(lineId, text, row, g.textLeft.toFloat(), emptyList()), replacing = null, ownRowFree = false)
        }

        /**
         * Fits [run] on page [i] and makes room for the rows it needs: its own row too unless [ownRowFree] (a run in
         * place of its segment or its old run owns that row). Then stores it, replacing [replacing].
         */
        private fun put(
            i: Int,
            run: TextRun,
            replacing: TextRun?,
            ownRowFree: Boolean = true,
        ) {
            val others = page(i).text.filter { it !== replacing }
            val (x0, column) = column(run.x0, rightBound(page(i), run, others))
            val rows = fit(run.display, column, measure, run.heading)
            val placed = run.copy(x0 = x0, column = column, scale = TEXT_SCALE, wrapRows = rows)
            setPage(i, page(i).copy(text = others))
            val from = if (ownRowFree) placed.row + 1 else placed.row
            val needed = from until placed.row + rows
            val firstTaken = needed.firstOrNull { occupied(page(i), it) }
            if (firstTaken != null) shift(i, firstTaken, placed.row + rows - firstTaken)
            setPage(i, page(i).copy(text = page(i).text + placed))
            markDirty(i, placed.row until placed.row + maxOf(rows, replacing?.wrapRows ?: 0))
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

        /**
         * x0 and width of the column from [x0] to [right], floored at [MIN_COLUMN_CELLS] cells: a narrower one would wrap
         * into a row per character or two. The floor extends past [right] (a neighbour), and x0 moves left (not past
         * the text column start) when the margin is closer.
         */
        private fun column(
            x0: Float,
            right: Float,
        ): Pair<Float, Float> {
            val floor = (MIN_COLUMN_CELLS * g.cell).toFloat()
            if (right - x0 >= floor) return x0 to right - x0
            val left = minOf(x0, g.marginX - floor).coerceAtLeast(g.textLeft.toFloat())
            return left to maxOf(right - left, floor)
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
            if (stack !=
                null
            ) {
                n = stack.shiftRows(n, i, fromY, dy)
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
                val centres = shiftCentres(p.strokes)
                val strokes = p.strokes.filter { it.points.isNotEmpty() && rowOf(centres.getValue(it.id)) >= cut }
                val segments = p.pendingSegments.filter { it.row >= cut }
                if (runs.isEmpty() && strokes.isEmpty() && segments.isEmpty()) return
                val ids = strokes.map { it.id }.toSet()
                undo?.forget(i)
                setPage(
                    i,
                    p.copy(
                        text = p.text - runs.toSet(),
                        strokes = p.strokes.filter { it.id !in ids },
                        pendingSegments = p.pendingSegments - segments.toSet(),
                        consumed = p.consumed - ids,
                        opConsumedAt = p.opConsumedAt - ids,
                        appliedRegions = p.appliedRegions - ids,
                    ),
                )
                val earlier = overflowOf[i]
                val target = earlier ?: insertAfter(i)
                undo?.forget(target)
                if (earlier != null) {
                    // An earlier overflow of this placement is there: these rows came before it on page i.
                    val height =
                        (
                            runs.map { it.row + it.wrapRows } + segments.map { it.row + 1 } +
                                strokes.map { rowOf(centreY(it)) + 1 }
                        ).max() - cut
                    shift(target, 0, height)
                }
                val dy = -(cut * g.rowPitch).toFloat()
                val t = page(target)
                setPage(
                    target,
                    t.copy(
                        text = t.text + runs.map { it.copy(row = it.row - cut) },
                        strokes = t.strokes + shiftStrokes(strokes, Float.NEGATIVE_INFINITY, dy),
                        pendingSegments = t.pendingSegments + segments.map { it.copy(row = it.row - cut) },
                        consumed = t.consumed + p.consumed.intersect(ids),
                        opConsumedAt = t.opConsumedAt + p.opConsumedAt.filterKeys { it in ids },
                        appliedRegions = t.appliedRegions + p.appliedRegions.filterKeys { it in ids },
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

/** A mermaid block the board shows as one typed [marker] line; [lines] are the 1-based source lines of the block. */
private class DiagramBlock(
    val marker: String,
    val lines: List<Int>,
)

/**
 * Mermaid fenced blocks drawn on the board with the Box tool: the opening fence has segment origins (the handwritten
 * labels) or already carries a marker run. The board shows one typed line instead of the boxes, arrows and labels (the
 * diagram itself renders in Documents).
 */
private object DiagramBlocks {
    private val OPEN = Regex("^\\s*(```|~~~)\\s*mermaid\\b", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("^\\s*(```|~~~)")
    private val EDGE_LABEL = Regex("(--+>|==+>|--+|==+|-\\.+->)\\s*\\|[^|]*\\|")
    private val SKIP_LINE = Regex("^\\s*(subgraph|end|style|classDef|class|linkStyle|click|direction|%%)\\b.*|^\\s*%%.*")
    private val NODE =
        Regex(
            "([A-Za-z_][\\w-]*)(?:\\s*(?:\\[\\s*\"([^\"]*)\"\\s*]|\\[([^\\]\"]*)]|\\(\\(?\"?([^)\"]*)\"?\\)?\\)|\\{\"?([^}\"]*)\"?}))?",
        )
    private val REWRITE = Regex("\\[\\[\\?([^|\\]]*)(?:\\|[^\\]]*)?]]")

    /** Diagram blocks by the 1-based source line of their opening fence. */
    fun find(
        source: List<String>,
        lines: List<SpecLineModel>,
        revert: Boolean,
        hasRun: (String) -> Boolean,
    ): Map<Int, DiagramBlock> {
        val bySource = lines.associateBy { it.sourceLine }
        val found = HashMap<Int, DiagramBlock>()
        var i = 0
        while (i < source.size) {
            if (!OPEN.containsMatchIn(source[i])) {
                i++
                continue
            }
            val close = (i + 1 until source.size).firstOrNull { FENCE.containsMatchIn(source[it]) } ?: source.size
            val block = (i + 1)..minOf(close + 1, source.size)
            val origins =
                !revert && block.any { n -> ((bySource[n]?.origin as? LineOrigin.Segments)?.ids ?: emptyList()).isNotEmpty() }
            val kept = bySource[i + 1]?.let { hasRun(it.lineId) } == true
            if (origins || kept) found[i + 1] = DiagramBlock(marker(source.subList(i + 1, close)), block.toList())
            i = close + 1
        }
        return found
    }

    /** "Diagram: A -> B (open in Documents)" from the node labels of the block body (ids when a node has no label). */
    private fun marker(body: List<String>): String {
        val nodes = LinkedHashMap<String, String?>()
        for (raw in body.drop(1)) {
            if (SKIP_LINE.matches(raw)) continue
            for (m in NODE.findAll(EDGE_LABEL.replace(raw, "$1"))) {
                val id = m.groupValues[1]
                val label = (2..5).map { m.groupValues[it] }.firstOrNull { it.isNotBlank() }?.let(::clean)
                if (id !in nodes || (nodes[id] == null && label != null)) nodes[id] = label
            }
        }
        val names = nodes.map { (id, label) -> label?.takeIf { it.isNotBlank() } ?: id }
        return if (names.isEmpty()) "Diagram (open in Documents)" else "Diagram: ${names.joinToString(" -> ")} (open in Documents)"
    }

    private fun clean(label: String) = SourceMap.stripWithColours(REWRITE.replace(label, "$1")).display.trim()

    /** Ink of page [p] that belongs to the diagram whose label strokes are [labels]: the boxes around them and the arrows between boxes. */
    fun ink(
        p: Page,
        labels: List<String>,
    ): List<String> {
        val live = p.visibleStrokes.filter { it.points.isNotEmpty() }
        val inks = live.filter { it.id in labels }.map(::bounds)
        val boxes =
            live
                .filter { it.tool == Tool.RECTANGLE && it.points.size >= 2 }
                .map { it to bounds(it) }
                .filter { (_, b) -> inks.any { l -> b.contains((l.l + l.r) / 2, (l.t + l.b) / 2) } }
        if (boxes.isEmpty()) return emptyList()
        val union =
            Bounds(boxes.minOf { it.second.l }, boxes.minOf { it.second.t }, boxes.maxOf { it.second.r }, boxes.maxOf { it.second.b })
        val arrows = live.filter { it.tool == Tool.ARROW && it.points.size >= 2 && union.containsAll(bounds(it)) }
        return (boxes.map { it.first } + arrows).map { it.id }.filter { it !in labels }
    }

    private fun bounds(s: Stroke) =
        Bounds(s.points.minOf { it.x }, s.points.minOf { it.y }, s.points.maxOf { it.x }, s.points.maxOf { it.y })

    private fun Bounds.contains(
        x: Float,
        y: Float,
    ) = x in l..r && y in t..b

    private fun Bounds.containsAll(o: Bounds) = o.l >= l && o.t >= t && o.r <= r && o.b <= b

    private class Bounds(
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float,
    )
}
