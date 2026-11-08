package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Command
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.UndoStack
import org.junit.Test

class RunPlacerTest {
    private val g = NotebookGeometry(1404, 1872)
    private val canvas = Canvas(1404, 1872, 300f)

    /** 20 px per char at scale 1: the text column from x 53 holds 46 chars, 54 at the 85 percent floor. */
    private val measure = TextMeasure { text, scale -> text.length * 20f * scale }

    private fun stroke(
        id: String,
        row: Int,
        x0: Float = 100f,
        x1: Float = 300f,
    ): Stroke {
        val y = g.rowTop(row) + 70f
        return Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(x0, y, 0.5f, 0L), Point(x1, y, 0.5f, 10L)))
    }

    private fun pending(
        segmentId: String,
        row: Int,
        vararg strokeIds: String,
        clientId: String = "c1",
        x0: Float = 100f,
        baseVersion: Int = 0,
    ) = PendingSegment(clientId, segmentId, row, x0, 300f, strokeIds.toList(), baseVersion)

    private fun notebook(vararg pages: Page) = Notebook(canvas, pages.toList(), 0)

    private fun run(
        lineId: String,
        row: Int,
        text: String,
        sourceLine: Int = 1,
        strokes: List<String> = emptyList(),
        x0: Float = 53f,
    ): TextRun {
        val s = SourceMap.stripWithColours(text)
        return TextRun(
            lineId,
            1,
            sourceLine,
            row,
            x0,
            display = s.display,
            colourSpans = s.spans,
            sourceMap = s.map,
            sourceStrokeIds = strokes,
        )
    }

    private fun line(
        id: String,
        n: Int,
        origin: LineOrigin?,
    ) = SpecLineModel(id, n, origin)

    private fun apply(
        n: Notebook,
        markdown: String,
        vararg lines: SpecLineModel,
        version: Int = 2,
        revertedFrom: Int? = null,
        undo: UndoStack? = null,
    ) = RunPlacer.apply(n, version, markdown, SpecLayoutModel(lines.toList(), revertedFrom = revertedFrom), measure, g, undo)

    private fun Notebook.runs(page: Int = 0) = pages[page].text

    @Test
    fun segment_line_placed_on_its_row_at_x0_and_strokes_consumed() {
        val n = notebook(Page(listOf(stroke("a", 4), stroke("b", 4)), false, pendingSegments = listOf(pending("p0-r4-0", 4, "a", "b"))))
        val p = apply(n, "buy milk", line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))))
        val page = p.notebook.pages[0]
        assertThat(page.text).hasSize(1)
        val r = page.text[0]
        assertThat(r.lineId).isEqualTo("L1")
        assertThat(r.row).isEqualTo(4)
        assertThat(r.x0).isEqualTo(100f)
        assertThat(r.display).isEqualTo("buy milk")
        assertThat(r.specVersion).isEqualTo(2)
        assertThat(r.sourceStrokeIds).containsExactly("a", "b")
        assertThat(page.consumed).containsExactly("a", "b")
        assertThat(page.visibleStrokes).isEmpty()
        assertThat(page.pendingSegments).isEmpty()
        assertThat(p.notebook.appliedSpecVersion).isEqualTo(2)
        assertThat(p.changedRows[0]).isEqualTo(4..4)
    }

    @Test
    fun kept_line_updates_text_in_place() {
        val n = notebook(Page(emptyList(), false, text = listOf(run("L1", 6, "old text", strokes = listOf("x")))))
        val p = apply(n, "# Title\n**new** text", line("L0", 1, null), line("L1", 2, LineOrigin.Kept("L1")))
        val r = p.notebook.runs().single { it.lineId == "L1" }
        assertThat(r.row).isEqualTo(6)
        assertThat(r.display).isEqualTo("new text")
        assertThat(r.sourceLine).isEqualTo(2)
        assertThat(r.specVersion).isEqualTo(2)
        assertThat(r.sourceStrokeIds).containsExactly("x")
    }

    @Test
    fun heading_runs_are_marked() {
        val n = notebook(Page(emptyList(), false, text = listOf(run("L1", 6, "old"))))
        val r = apply(n, "## Plan", line("L1", 1, LineOrigin.Kept("L1"))).notebook.runs().single()
        assertThat(r.heading).isTrue()
        assertThat(r.display).isEqualTo("Plan")
    }

    @Test
    fun removed_line_drops_run() {
        val n =
            notebook(Page(listOf(stroke("x", 3)), false, text = listOf(run("L1", 3, "gone", strokes = listOf("x"))), consumed = setOf("x")))
        val p = apply(n, "other", line("L2", 1, null))
        assertThat(p.notebook.runs().map { it.lineId }).doesNotContain("L1")
        // A normal version keeps the ink of a removed line hidden.
        assertThat(p.notebook.pages[0].consumed).containsExactly("x")
    }

    @Test
    fun long_line_wraps_at_the_one_text_size() {
        // 50 chars: 1000 px at scale 1 > 930 px column; no shrink to fit, it wraps at scale 1.
        val text = "a".repeat(50)
        val n = notebook(Page(emptyList(), false, text = listOf(run("L1", 2, "x"))))
        val r = apply(n, text, line("L1", 1, LineOrigin.Kept("L1"))).notebook.runs().single()
        assertThat(r.scale).isEqualTo(RunPlacer.TEXT_SCALE)
        assertThat(r.wrapRows).isEqualTo(2)
        val wide = apply(n, "a".repeat(30) + " " + "b".repeat(29), line("L1", 1, LineOrigin.Kept("L1"))).notebook.runs().single()
        assertThat(wide.scale).isEqualTo(RunPlacer.TEXT_SCALE)
        assertThat(wide.wrapRows).isEqualTo(2)
    }

    @Test
    fun every_line_gets_one_size_and_unchanged_lines_are_rendered_again() {
        // Version 1 left L1 at an older shrunk size; version 2 changes only L2, to a line wider than the column.
        val n =
            notebook(
                Page(
                    emptyList(),
                    false,
                    text = listOf(run("L1", 2, "short").copy(scale = 0.9f), run("L2", 4, "x", sourceLine = 2)),
                ),
            )
        val long = "word ".repeat(10) + "end"
        val p = apply(n, "short\n$long", line("L1", 1, LineOrigin.Kept("L1")), line("L2", 2, LineOrigin.Kept("L2")))
        val runs = p.notebook.runs()
        assertThat(runs.map { it.scale }.toSet()).containsExactly(RunPlacer.TEXT_SCALE)
        // The unchanged line is laid out and rendered again, not kept from the earlier render.
        assertThat(p.changedRows[0]!!.contains(2)).isTrue()
        assertThat(p.changedRows[0]!!.contains(4)).isTrue()
    }

    @Test
    fun wrap_breaks_at_word_boundaries() {
        val parts = RunPlacer.wrap("aaaa bbbb cccc", 1f, 150f, measure)
        assertThat(parts.map { "aaaa bbbb cccc".substring(it.first, it.last + 1) }).containsExactly("aaaa", "bbbb", "cccc").inOrder()
    }

    @Test
    fun longer_line_wraps_and_shifts_rows_below_with_their_ink() {
        val below = stroke("ink5", 5)
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 4), below, stroke("top", 1)),
                    false,
                    text = listOf(run("L9", 6, "next")),
                    pendingSegments = listOf(pending("p0-r4-0", 4, "a", x0 = 53f)),
                ),
            )
        val text = "w".repeat(40) + " " + "z".repeat(40)
        val p = apply(n, "$text\nnext", line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))), line("L9", 2, LineOrigin.Kept("L9")))
        val page = p.notebook.pages[0]
        val placed = page.text.single { it.lineId == "L1" }
        assertThat(placed.row).isEqualTo(4)
        assertThat(placed.wrapRows).isEqualTo(2)
        // Row 5 was taken by ink: rows 5 and below move down one row, with their ink and runs.
        assertThat(page.text.single { it.lineId == "L9" }.row).isEqualTo(7)
        val moved = page.strokes.single { it.id == "ink5" }
        assertThat(moved.points[0].y).isEqualTo(below.points[0].y + g.rowPitch)
        assertThat(
            page.strokes
                .single { it.id == "top" }
                .points[0]
                .y,
        ).isEqualTo(g.rowTop(1) + 70f)
        assertThat(p.changedRows[0]!!.first).isEqualTo(4)
        assertThat(p.changedRows[0]!!.last).isEqualTo(g.rowCount - 1)
    }

    @Test
    fun shift_is_not_an_undo_step_and_history_follows_it() {
        val below = stroke("ink5", 5)
        val mine = stroke("mine", 7)
        val undo = UndoStack()
        val n =
            undo.apply(
                notebook(Page(listOf(stroke("a", 4), below), false, pendingSegments = listOf(pending("p0-r4-0", 4, "a", x0 = 53f)))),
                Command.AddStroke(mine),
            )
        val text = "w".repeat(40) + " " + "z".repeat(40)
        val placed = apply(n, text, line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))), undo = undo).notebook

        fun Notebook.y(id: String) =
            pages[0]
                .strokes
                .single { it.id == id }
                .points[0]
                .y
        assertThat(placed.y("ink5")).isEqualTo(below.points[0].y + g.rowPitch)
        assertThat(placed.y("mine")).isEqualTo(mine.points[0].y + g.rowPitch)
        // Undo takes back the user's last stroke, not the placement's shift: ink stays aligned with the text.
        val undone = undo.undo(placed)
        assertThat(undone.pages[0].strokes.map { it.id }).containsExactly("a", "ink5")
        assertThat(undone.y("ink5")).isEqualTo(below.points[0].y + g.rowPitch)
        assertThat(
            undone.pages[0]
                .text
                .single()
                .row,
        ).isEqualTo(4)
        assertThat(undo.canUndo(undone)).isFalse()
        // Redo puts it back where its row went.
        assertThat(undo.redo(undone).y("mine")).isEqualTo(mine.points[0].y + g.rowPitch)
    }

    @Test
    fun shift_without_history_leaves_nothing_to_undo() {
        val n =
            notebook(Page(listOf(stroke("a", 4), stroke("ink5", 5)), false, pendingSegments = listOf(pending("p0-r4-0", 4, "a", x0 = 53f))))
        val undo = UndoStack()
        val text = "w".repeat(40) + " " + "z".repeat(40)
        val placed = apply(n, text, line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))), undo = undo).notebook
        assertThat(undo.canUndo(placed)).isFalse()
        assertThat(placed.pages[0].changedSinceSend).isFalse()
    }

    @Test
    fun shift_past_last_row_moves_overflow_to_a_new_page_after() {
        val last = g.rowCount - 1
        val n =
            notebook(
                Page(
                    listOf(stroke("a", last - 1), stroke("foot", last)),
                    false,
                    text = listOf(run("L9", last, "tail", sourceLine = 2)),
                    pendingSegments = listOf(pending("p0-r14-0", last - 1, "a", x0 = 53f)),
                ),
                Page(listOf(stroke("other", 2)), false),
            )
        val text = "w".repeat(40) + " " + "z".repeat(40)
        val p = apply(n, "$text\ntail", line("L1", 1, LineOrigin.Segments(listOf("p0-r14-0"))), line("L9", 2, LineOrigin.Kept("L9")))
        val pages = p.notebook.pages
        assertThat(pages).hasSize(3)
        // The wrapped run fills rows 14 and 15; the old last row (run and ink) moves to a new page right after.
        assertThat(pages[0].text.single().lineId).isEqualTo("L1")
        assertThat(pages[0].text.single().row + pages[0].text.single().wrapRows).isAtMost(g.rowCount)
        assertThat(pages[0].strokes.map { it.id }).containsExactly("a")
        assertThat(pages[1].text.single().lineId).isEqualTo("L9")
        assertThat(pages[1].text.single().row).isEqualTo(0)
        val foot = pages[1].strokes.single()
        assertThat(foot.id).isEqualTo("foot")
        assertThat(g.rowAt(foot.points[0].y)).isEqualTo(0)
        assertThat(pages[2].strokes.single().id).isEqualTo("other")
        for (page in pages) for (r in page.text) assertThat(r.row + r.wrapRows).isAtMost(g.rowCount)
    }

    @Test
    fun op_consumed_stroke_that_overflows_keeps_its_version_and_is_restored_by_a_revert() {
        val last = g.rowCount - 1
        val n =
            notebook(
                Page(
                    listOf(stroke("a", last - 1), stroke("foot", last)),
                    false,
                    text = listOf(run("L9", last, "tail", sourceLine = 2)),
                    pendingSegments = listOf(pending("p0-r14-0", last - 1, "a", x0 = 53f)),
                    consumed = setOf("foot"),
                    opConsumedAt = mapOf("foot" to 1),
                ),
            )
        val text = "w".repeat(40) + " " + "z".repeat(40)
        val p = apply(n, "$text\ntail", line("L1", 1, LineOrigin.Segments(listOf("p0-r14-0"))), line("L9", 2, LineOrigin.Kept("L9")))
        assertThat(p.notebook.pages[0].opConsumedAt).isEmpty()
        assertThat(p.notebook.pages[1].opConsumedAt).containsExactly("foot", 1)
        val back =
            apply(
                p.notebook,
                "$text\ntail",
                line("L1", 1, LineOrigin.Kept("L1")),
                line("L9", 2, LineOrigin.Kept("L9")),
                version = 3,
                revertedFrom = 0,
            )
        val page = back.notebook.pages[1]
        assertThat(page.opConsumedAt).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).contains("foot")
    }

    @Test
    fun fenced_lines_not_placed_ink_kept() {
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 4), stroke("t", 6)),
                    false,
                    pendingSegments = listOf(pending("p0-r4-0", 4, "a"), pending("p0-r6-0", 6, "t")),
                ),
            )
        val md = "```mermaid\ngraph TD\n```\n| a | b |"
        val p =
            apply(
                n,
                md,
                line("L1", 1, null),
                line("L2", 2, LineOrigin.Segments(listOf("p0-r4-0"))),
                line("L3", 3, null),
                line("L4", 4, LineOrigin.Segments(listOf("p0-r6-0"))),
            )
        val page = p.notebook.pages[0]
        assertThat(page.text).isEmpty()
        assertThat(page.consumed).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("a", "t")
        assertThat(page.pendingSegments).isEmpty()
    }

    @Test
    fun origin_null_line_placed_after_previous_run() {
        val n =
            notebook(
                Page(
                    listOf(stroke("ink3", 3)),
                    false,
                    text = listOf(run("L1", 2, "first"), run("L2", 8, "later", sourceLine = 2)),
                ),
            )
        val p =
            apply(n, "first\nadded\nlater", line("L1", 1, LineOrigin.Kept("L1")), line("L5", 2, null), line("L2", 3, LineOrigin.Kept("L2")))
        val page = p.notebook.pages[0]
        val added = page.text.single { it.lineId == "L5" }
        assertThat(added.row).isEqualTo(3)
        assertThat(added.x0).isEqualTo(g.textLeft.toFloat())
        // Row 3 held ink: it and everything below moved one row down.
        assertThat(
            g.rowAt(
                page.strokes
                    .single()
                    .points[0]
                    .y,
            ),
        ).isEqualTo(4)
        assertThat(page.text.single { it.lineId == "L2" }.row).isEqualTo(9)
    }

    @Test
    fun origin_null_line_on_a_free_row_shifts_nothing() {
        val n = notebook(Page(emptyList(), false, text = listOf(run("L1", 2, "first"), run("L2", 8, "later", sourceLine = 2))))
        val p =
            apply(n, "first\nadded\nlater", line("L1", 1, LineOrigin.Kept("L1")), line("L5", 2, null), line("L2", 3, LineOrigin.Kept("L2")))
        assertThat(
            p.notebook
                .runs()
                .single { it.lineId == "L5" }
                .row,
        ).isEqualTo(3)
        assertThat(
            p.notebook
                .runs()
                .single { it.lineId == "L2" }
                .row,
        ).isEqualTo(8)
    }

    @Test
    fun revert_removes_runs_and_restores_their_ink() {
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 2), stroke("b", 5)),
                    false,
                    text =
                        listOf(
                            run("L1", 2, "kept", strokes = listOf("a")),
                            run("L2", 5, "later", sourceLine = 2, strokes = listOf("b")),
                        ),
                    consumed = setOf("a", "b"),
                ),
            )
        val p = apply(n, "kept", line("L1", 1, LineOrigin.Segments(listOf("p0-r2-0"))), version = 4, revertedFrom = 1)
        val page = p.notebook.pages[0]
        assertThat(page.text.map { it.lineId }).containsExactly("L1")
        assertThat(page.text.single().row).isEqualTo(2)
        assertThat(page.consumed).containsExactly("a")
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("b")
    }

    @Test
    fun segments_of_an_answered_upload_not_in_any_line_are_dropped() {
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 4), stroke("b", 7), stroke("c", 9)),
                    false,
                    pendingSegments =
                        listOf(
                            pending("p0-r4-0", 4, "a"),
                            pending("p0-r7-0", 7, "b"),
                            pending("p0-r9-0", 9, "c", clientId = "c2", baseVersion = 2),
                        ),
                ),
            )
        val p = apply(n, "a", line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))))
        val page = p.notebook.pages[0]
        assertThat(page.pendingSegments.map { it.segmentId }).containsExactly("p0-r9-0")
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("b", "c")
    }

    @Test
    fun two_runs_on_one_row_share_it() {
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 4, 53f, 200f), stroke("b", 4, 600f, 700f)),
                    false,
                    pendingSegments = listOf(pending("p0-r4-0", 4, "a", x0 = 53f), pending("p0-r4-1", 4, "b", x0 = 600f)),
                ),
            )
        val p =
            apply(
                n,
                "x".repeat(30) + "\nright",
                line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))),
                line("L2", 2, LineOrigin.Segments(listOf("p0-r4-1"))),
            )
        val left = p.notebook.runs().single { it.lineId == "L1" }
        // The left run ends before the right one starts: 30 chars do not fit 547 px, so it shrinks or wraps.
        assertThat(measure.width(left.display, left.scale) <= 600f - 53f || left.wrapRows > 1).isTrue()
    }

    @Test
    fun revert_marks_pages_with_restored_ink_changed() {
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 2), stroke("b", 5)),
                    false,
                    text =
                        listOf(
                            run("L1", 2, "kept", strokes = listOf("a")),
                            run("L2", 5, "later", sourceLine = 2, strokes = listOf("b")),
                        ),
                    consumed = setOf("a", "b"),
                ),
                Page(
                    listOf(stroke("c", 3)),
                    false,
                    text = listOf(run("L3", 3, "other", sourceLine = 3, strokes = listOf("c"))),
                    consumed = setOf("c"),
                ),
            )
        val p =
            apply(
                n,
                "kept\n\nother",
                line("L1", 1, LineOrigin.Kept("L1")),
                line("L3", 3, LineOrigin.Kept("L3")),
                version = 4,
                revertedFrom = 1,
            )
        // The un-hidden ink of L2 is re-sendable: Send of changed pages picks page 0 up.
        assertThat(p.notebook.pages[0].changedSinceSend).isTrue()
        assertThat(p.notebook.pages[1].changedSinceSend).isFalse()
    }

    @Test
    fun narrow_column_at_the_margin_moves_the_run_left_instead_of_one_char_rows() {
        val x0 = g.marginX - 20f
        val n = notebook(Page(listOf(stroke("a", 4, x0, x0 + 15f)), false, pendingSegments = listOf(pending("p0-r4-0", 4, "a", x0 = x0))))
        val p = apply(n, "hello world!", line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))))
        val run = p.notebook.runs().single()
        assertThat(run.wrapRows).isEqualTo(1)
        assertThat(run.x0).isAtLeast(g.textLeft.toFloat())
        assertThat(run.x0 + measure.width(run.display, run.scale)).isAtMost(g.marginX + 0.5f)
    }

    @Test
    fun narrow_column_before_a_neighbour_is_floored() {
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 4, 400f, 410f)),
                    false,
                    text = listOf(run("L9", 4, "right", sourceLine = 2, x0 = 430f)),
                    pendingSegments = listOf(pending("p0-r4-0", 4, "a", x0 = 400f)),
                ),
            )
        val p =
            apply(
                n,
                "hello world again\nright",
                line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))),
                line("L9", 2, LineOrigin.Kept("L9")),
            )
        val run = p.notebook.runs().single { it.lineId == "L1" }
        // 30 px to the neighbour would give one or two chars per row; the floor keeps it to a few rows.
        assertThat(run.wrapRows).isAtMost(2)
        assertThat(run.column).isAtLeast(RunPlacer.MIN_COLUMN_CELLS * g.cell.toFloat())
    }

    @Test
    fun placed_column_is_kept_on_the_run() {
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 4, 53f, 200f), stroke("b", 4, 600f, 700f)),
                    false,
                    pendingSegments = listOf(pending("p0-r4-0", 4, "a", x0 = 53f), pending("p0-r4-1", 4, "b", x0 = 600f)),
                ),
            )
        val p =
            apply(
                n,
                "x".repeat(30) + "\nright",
                line("L1", 1, LineOrigin.Segments(listOf("p0-r4-0"))),
                line("L2", 2, LineOrigin.Segments(listOf("p0-r4-1"))),
            )
        assertThat(
            p.notebook
                .runs()
                .single { it.lineId == "L1" }
                .column,
        ).isEqualTo(600f - 53f)
    }

    @Test
    fun second_overflow_of_a_page_goes_before_the_first_on_the_overflow_page() {
        val long = "w".repeat(40) + " " + "z".repeat(40)
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 10, 53f), stroke("i11", 11), stroke("b", 12, 53f), stroke("x13", 13), stroke("z", 15)),
                    false,
                    text = listOf(run("L9", 14, "tail", sourceLine = 3)),
                    pendingSegments = listOf(pending("p0-r10-0", 10, "a", x0 = 53f), pending("p0-r12-0", 12, "b", x0 = 53f)),
                ),
            )
        val p =
            apply(
                n,
                "$long\n$long\ntail",
                line("L1", 1, LineOrigin.Segments(listOf("p0-r10-0"))),
                line("L2", 2, LineOrigin.Segments(listOf("p0-r12-0"))),
                line("L9", 3, LineOrigin.Kept("L9")),
            )
        val pages = p.notebook.pages
        assertThat(pages).hasSize(2)
        val over = pages[1]
        // L9 came before the ink z on page 0: it leads the overflow page, z follows on the next row.
        assertThat(over.text.single().lineId).isEqualTo("L9")
        assertThat(over.text.single().row).isEqualTo(0)
        assertThat(
            g.rowAt(
                over.strokes
                    .single { it.id == "z" }
                    .points[0]
                    .y,
            ),
        ).isEqualTo(1)
        for (page in pages) for (r in page.text) assertThat(r.row + r.wrapRows).isAtMost(g.rowCount)
    }

    @Test
    fun revert_ignores_segment_origins_of_the_old_version() {
        val n =
            notebook(
                Page(
                    listOf(stroke("new", 4)),
                    false,
                    text = listOf(run("L1", 2, "kept")),
                    pendingSegments = listOf(pending("p0-r4-0", 4, "new", clientId = "c2", baseVersion = 5)),
                ),
            )
        val p =
            apply(
                n,
                "kept\nold line",
                line("L1", 1, LineOrigin.Kept("L1")),
                line("L2", 2, LineOrigin.Segments(listOf("p0-r4-0"))),
                version = 5,
                revertedFrom = 2,
            )
        val page = p.notebook.pages[0]
        // The new, unanswered handwriting with the same segment id stays pending and visible.
        assertThat(page.pendingSegments.map { it.clientId }).containsExactly("c2")
        assertThat(page.consumed).isEmpty()
        assertThat(page.text.single { it.lineId == "L2" }.row).isEqualTo(3)
    }

    @Test
    fun first_origin_null_line_goes_before_the_next_lines_run() {
        val n =
            notebook(
                Page(listOf(stroke("ink0", 0)), false),
                Page(emptyList(), false, text = listOf(run("L2", 3, "later", sourceLine = 2))),
            )
        val p = apply(n, "added\nlater", line("L5", 1, null), line("L2", 2, LineOrigin.Kept("L2")))
        assertThat(p.notebook.runs(0)).isEmpty()
        assertThat(
            g.rowAt(
                p.notebook.pages[0]
                    .strokes
                    .single()
                    .points[0]
                    .y,
            ),
        ).isEqualTo(0)
        // Row 2 above L2 is free: the new line goes there and nothing moves.
        assertThat(
            p.notebook
                .runs(1)
                .single { it.lineId == "L5" }
                .row,
        ).isEqualTo(2)
        assertThat(
            p.notebook
                .runs(1)
                .single { it.lineId == "L2" }
                .row,
        ).isEqualTo(3)
    }

    @Test
    fun first_origin_null_line_pushes_the_next_run_down_when_the_row_above_is_taken() {
        val n = notebook(Page(listOf(stroke("ink2", 2)), false, text = listOf(run("L2", 3, "later", sourceLine = 2))))
        val p = apply(n, "added\nlater", line("L5", 1, null), line("L2", 2, LineOrigin.Kept("L2")))
        assertThat(
            p.notebook
                .runs()
                .single { it.lineId == "L5" }
                .row,
        ).isEqualTo(3)
        assertThat(
            p.notebook
                .runs()
                .single { it.lineId == "L2" }
                .row,
        ).isEqualTo(4)
    }
}
