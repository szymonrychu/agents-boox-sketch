package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test

/** Placement of versions that report edit ops (Task 5.3): op lines, applied and rejected ops. */
class RunPlacerOpsTest {
    private val g = NotebookGeometry(1404, 1872)
    private val canvas = Canvas(1404, 1872, 300f)
    private val measure = TextMeasure { text, scale -> text.length * 20f * scale }

    private fun stroke(
        id: String,
        row: Int,
    ): Stroke {
        val y = g.rowTop(row) + 70f
        return Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(100f, y, 0.5f, 0L), Point(300f, y, 0.5f, 10L)))
    }

    private fun notebook(vararg pages: Page) = Notebook(canvas, pages.toList(), 0)

    private fun run(
        lineId: String,
        row: Int,
        text: String,
        sourceLine: Int = 1,
    ) = NotebookFixtures.run(lineId, row, text, sourceLine = sourceLine)

    private fun line(
        id: String,
        n: Int,
        origin: LineOrigin?,
    ) = SpecLineModel(id, n, origin)

    /** A stroke at page y [y] from x 140 to 240. */
    private fun mark(
        id: String,
        y: Float,
    ) = Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(140f, y, 0.5f, 0L), Point(240f, y, 0.5f, 10L)))

    private fun withOp(
        n: Notebook,
        op: EditOp,
    ) = n.copy(pages = n.pages.mapIndexed { i, p -> if (i == 0) p.copy(pendingOps = listOf(PendingOp("c1", op))) else p })

    private fun applyOps(
        n: Notebook,
        markdown: String,
        ops: List<OpStatusModel>,
        vararg lines: SpecLineModel,
    ) = RunPlacer.apply(n, 2, markdown, SpecLayoutModel(lines.toList(), ops = ops, baseVersion = 1), measure, g)

    @Test
    fun applied_replace_updates_words_and_consumes_annotation() {
        val above = mark("w", g.rowTop(4) + 26f)
        val op = EditOp("op1", OpKind.REPLACE, OpTarget("L1", 4, 9, "fresh"), strokeIds = listOf("w"))
        val n = withOp(notebook(Page(listOf(above), false, text = listOf(run("L1", 4, "buy fresh milk")))), op)
        val p = applyOps(n, "buy organic milk", listOf(OpStatusModel("op1", true)), line("L1", 1, LineOrigin.Kept("L1")))
        val page = p.notebook.pages[0]
        assertThat(page.text.single().display).isEqualTo("buy organic milk")
        assertThat(page.text.single().row).isEqualTo(4)
        assertThat(page.consumed).containsExactly("w")
        assertThat(page.visibleStrokes).isEmpty()
        assertThat(page.pendingOps).isEmpty()
        assertThat(p.changedRows[0]).isNotNull()
        assertThat(p.changedRows[0]!!.contains(4)).isTrue()
    }

    private fun consumedByOpAt2(): Notebook {
        val above = mark("w", g.rowTop(4) + 26f)
        val op = EditOp("op1", OpKind.REPLACE, OpTarget("L1", 4, 9, "fresh"), strokeIds = listOf("w"))
        val n = withOp(notebook(Page(listOf(above), false, text = listOf(run("L1", 4, "buy fresh milk")))), op)
        return applyOps(n, "buy organic milk", listOf(OpStatusModel("op1", true)), line("L1", 1, LineOrigin.Kept("L1"))).notebook
    }

    private fun revertTo(
        n: Notebook,
        from: Int,
    ) = RunPlacer.apply(
        n,
        3,
        "buy fresh milk",
        SpecLayoutModel(listOf(line("L1", 1, LineOrigin.Kept("L1"))), revertedFrom = from),
        measure,
        g,
    )

    @Test
    fun applied_op_records_the_version_that_consumed_its_strokes() {
        assertThat(consumedByOpAt2().pages[0].opConsumedAt).containsExactly("w", 2)
    }

    @Test
    fun revert_to_a_version_before_the_op_restores_its_strokes() {
        val p = revertTo(consumedByOpAt2(), 1)
        val page = p.notebook.pages[0]
        assertThat(page.consumed).isEmpty()
        assertThat(page.opConsumedAt).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("w")
        assertThat(page.changedSinceSend).isTrue()
        assertThat(p.changedRows[0]).isNotNull()
        assertThat(p.changedRows[0]!!.contains(4)).isTrue()
    }

    @Test
    fun repeated_revert_is_idempotent() {
        val once = revertTo(consumedByOpAt2(), 1).notebook
        val twice = revertTo(once, 1)
        val page = twice.notebook.pages[0]
        assertThat(page.consumed).isEmpty()
        assertThat(page.opConsumedAt).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("w")
    }

    @Test
    fun op_reapplied_after_a_revert_hides_the_strokes_again_at_the_new_version() {
        val reverted = revertTo(consumedByOpAt2(), 1).notebook
        val op = EditOp("op2", OpKind.REPLACE, OpTarget("L1", 4, 9, "fresh"), strokeIds = listOf("w"))
        val again = withOp(reverted, op)
        val p =
            RunPlacer.apply(
                again,
                4,
                "buy organic milk",
                SpecLayoutModel(listOf(line("L1", 1, LineOrigin.Kept("L1"))), ops = listOf(OpStatusModel("op2", true)), baseVersion = 3),
                measure,
                g,
            )
        val page = p.notebook.pages[0]
        assertThat(page.consumed).containsExactly("w")
        assertThat(page.opConsumedAt).containsExactly("w", 4)
        assertThat(page.visibleStrokes).isEmpty()
    }

    @Test
    fun revert_to_a_version_after_the_op_keeps_its_strokes_hidden() {
        val p = revertTo(consumedByOpAt2(), 2)
        val page = p.notebook.pages[0]
        assertThat(page.consumed).containsExactly("w")
        assertThat(page.opConsumedAt).containsExactly("w", 2)
        assertThat(page.changedSinceSend).isFalse()
    }

    /** A margin note (pen "n" plus arrow "A" into row 4) sent as a note op, reported [applied] by version 2. */
    private fun noteReportedAt2(applied: Boolean): Placement {
        val note = Stroke("n", Tool.BALLPOINT, 2f, listOf(Point(1050f, 450f, 0.5f, 0L), Point(1200f, 450f, 0.5f, 10L)))
        val arrow = Stroke("A", Tool.ARROW, 4f, listOf(Point(1040f, 450f, 1f, 0L), Point(400f, g.rowTop(4) + 80f, 1f, 10L)))
        val op = EditOp("op1", OpKind.NOTE, strokeIds = listOf("n", "A"), rect = listOf(400f, 450f, 800f, 110f))
        val n = withOp(notebook(Page(listOf(note, arrow), false, text = listOf(run("L1", 4, "buy fresh milk")))), op)
        val markdown = if (applied) "buy fresh milk today" else "buy fresh milk"
        val status = OpStatusModel("op1", applied, "no line".takeUnless { applied })
        return applyOps(n, markdown, listOf(status), line("L1", 1, LineOrigin.Kept("L1")))
    }

    @Test
    fun applied_note_op_hides_its_strokes() {
        val page = noteReportedAt2(applied = true).notebook.pages[0]
        assertThat(page.consumed).containsExactly("n", "A")
        assertThat(page.opConsumedAt).containsExactly("n", 2, "A", 2)
        assertThat(page.visibleStrokes).isEmpty()
        assertThat(page.pendingOps).isEmpty()
    }

    @Test
    fun revert_before_an_applied_note_op_restores_its_strokes() {
        val p = revertTo(noteReportedAt2(applied = true).notebook, 1)
        val page = p.notebook.pages[0]
        assertThat(page.consumed).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("n", "A")
        assertThat(page.changedSinceSend).isTrue()
    }

    @Test
    fun rejected_note_op_stays_plain_ink() {
        val page = noteReportedAt2(applied = false).notebook.pages[0]
        assertThat(page.consumed).isEmpty()
        assertThat(page.rejectedStrokes).containsExactly("n", "A")
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("n", "A")
        assertThat(page.pendingOps).isEmpty()
    }

    @Test
    fun rejected_op_keeps_ink() {
        val above = mark("w", g.rowTop(4) + 26f)
        val op = EditOp("op1", OpKind.REPLACE, OpTarget("L1", 4, 9, "fresh"), strokeIds = listOf("w"))
        val n = withOp(notebook(Page(listOf(above), false, text = listOf(run("L1", 4, "buy fresh milk")))), op)
        val p =
            applyOps(n, "buy fresh milk", listOf(OpStatusModel("op1", false, "illegible")), line("L1", 1, LineOrigin.Kept("L1")))
        val page = p.notebook.pages[0]
        assertThat(page.text.single().display).isEqualTo("buy fresh milk")
        assertThat(page.consumed).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("w")
        assertThat(page.pendingOps).isEmpty()
    }

    @Test
    fun rejected_op_marks_its_strokes_rejected() {
        val above = mark("w", g.rowTop(4) + 26f)
        val op = EditOp("op1", OpKind.REPLACE, OpTarget("L1", 4, 9, "fresh"), strokeIds = listOf("w"))
        val n = withOp(notebook(Page(listOf(above), false, text = listOf(run("L1", 4, "buy fresh milk")))), op)
        val p =
            applyOps(n, "buy fresh milk", listOf(OpStatusModel("op1", false, "illegible")), line("L1", 1, LineOrigin.Kept("L1")))
        assertThat(p.notebook.pages[0].rejectedStrokes).containsExactly("w")
    }

    @Test
    fun applied_insert_after_line_takes_next_row_and_shifts() {
        val note = Stroke("n", Tool.BALLPOINT, 2f, listOf(Point(1050f, 450f, 0.5f, 0L), Point(1200f, 450f, 0.5f, 10L)))
        val box = Stroke("R", Tool.RECTANGLE, 4f, listOf(Point(1000f, 400f, 1f, 0L), Point(1300f, 500f, 1f, 10L)))
        val arrow = Stroke("A", Tool.ARROW, 4f, listOf(Point(1000f, 450f, 1f, 0L), Point(100f, g.rowTop(5) + 20f, 1f, 10L)))
        val ink = stroke("ink6", 6)
        val op = EditOp("op1", OpKind.INSERT, at = OpAt("L1", 14, AtMode.AFTER_LINE), strokeIds = listOf("n", "R", "A"))
        val runs = listOf(run("L1", 4, "buy fresh milk"), run("L2", 5, "eggs", sourceLine = 2))
        val n = withOp(notebook(Page(listOf(note, box, arrow, ink), false, text = runs)), op)
        val p =
            applyOps(
                n,
                "buy fresh milk\nand bread\neggs",
                listOf(OpStatusModel("op1", true)),
                line("L1", 1, LineOrigin.Kept("L1")),
                line("L3", 2, LineOrigin.Op("op1")),
                line("L2", 3, LineOrigin.Kept("L2")),
            )
        val page = p.notebook.pages[0]
        assertThat(page.text.single { it.lineId == "L3" }.row).isEqualTo(5)
        assertThat(page.text.single { it.lineId == "L3" }.display).isEqualTo("and bread")
        // The next row was taken: L2 and the ink below move down one row.
        assertThat(page.text.single { it.lineId == "L2" }.row).isEqualTo(6)
        assertThat(
            page.strokes
                .single { it.id == "ink6" }
                .points[0]
                .y,
        ).isEqualTo(ink.points[0].y + g.rowPitch)
        assertThat(page.consumed).containsExactly("n", "R", "A")
        assertThat(page.pendingOps).isEmpty()
    }

    @Test
    fun applied_delete_removes_words() {
        val strike = mark("s", g.textBandTop(4) + 30f)
        val op = EditOp("op1", OpKind.DELETE, OpTarget("L1", 4, 10, "fresh "), strokeIds = listOf("s"))
        val n = withOp(notebook(Page(listOf(strike), false, text = listOf(run("L1", 4, "buy fresh milk")))), op)
        val p = applyOps(n, "buy milk", listOf(OpStatusModel("op1", true)), line("L1", 1, LineOrigin.Kept("L1")))
        val page = p.notebook.pages[0]
        assertThat(page.text.single().display).isEqualTo("buy milk")
        assertThat(page.consumed).containsExactly("s")
        assertThat(page.pendingOps).isEmpty()
    }

    @Test
    fun a_delete_of_a_whole_line_drops_its_run_and_consumes_the_strike() {
        val strike = mark("s", g.textBandTop(5) + 30f)
        val op = EditOp("op1", OpKind.DELETE, OpTarget("L2", 0, 4, "eggs"), strokeIds = listOf("s"))
        val runs = listOf(run("L1", 4, "milk"), run("L2", 5, "eggs", sourceLine = 2))
        val n = withOp(notebook(Page(listOf(strike), false, text = runs)), op)
        val p = applyOps(n, "milk", listOf(OpStatusModel("op1", true)), line("L1", 1, LineOrigin.Kept("L1")))
        val page = p.notebook.pages[0]
        assertThat(page.text.map { it.lineId }).containsExactly("L1")
        assertThat(page.consumed).containsExactly("s")
    }

    private fun pendingBoard(opBase: Int): Notebook {
        val above = mark("w", g.rowTop(4) + 26f)
        val op = EditOp("op1", OpKind.REPLACE, OpTarget("L1", 4, 9, "fresh"), strokeIds = listOf("w"))
        val ink = stroke("h", 8)
        val seg = PendingSegment("c1", "p0-r8-0", 8, 100f, 300f, listOf("h"), baseVersion = opBase)
        val page =
            Page(listOf(above, ink), false, text = listOf(run("L1", 4, "buy fresh milk")), pendingOps = listOf(PendingOp("c1", op, opBase)))
        return notebook(page.copy(pendingSegments = listOf(seg)))
    }

    @Test
    fun newer_version_that_does_not_report_the_pending_releases_ops_and_segments() {
        val p = applyOps(pendingBoard(1), "buy fresh milk", emptyList(), line("L1", 1, LineOrigin.Kept("L1")))
        val page = p.notebook.pages[0]
        assertThat(page.pendingOps).isEmpty()
        assertThat(page.pendingSegments).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("w", "h")
        assertThat(page.consumed).isEmpty()
        assertThat(page.changedSinceSend).isTrue()
        assertThat(p.changedRows[0]!!.contains(8)).isTrue()
    }

    @Test
    fun version_not_newer_than_the_upload_keeps_the_pending() {
        val p = applyOps(pendingBoard(2), "buy fresh milk", emptyList(), line("L1", 1, LineOrigin.Kept("L1")))
        assertThat(p.notebook.pages[0].pendingOps).hasSize(1)
        assertThat(p.notebook.pages[0].pendingSegments).hasSize(1)
    }

    @Test
    fun release_all_clears_every_pending() {
        val p = RunPlacer.releasePending(pendingBoard(5), null, g)
        assertThat(p.notebook.pages[0].pendingOps).isEmpty()
        assertThat(p.notebook.pages[0].pendingSegments).isEmpty()
        assertThat(p.notebook.pages[0].changedSinceSend).isTrue()
    }
}
