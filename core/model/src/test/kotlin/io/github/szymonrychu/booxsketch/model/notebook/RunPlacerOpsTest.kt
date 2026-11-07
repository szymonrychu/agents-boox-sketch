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
}
