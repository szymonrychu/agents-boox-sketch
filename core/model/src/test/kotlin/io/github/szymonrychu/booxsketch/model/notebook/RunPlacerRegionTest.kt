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

/** Image regions in a placement (Task 6.3): applied image ops, their ink and their image lines. */
class RunPlacerRegionTest {
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
    ): TextRun {
        val s = SourceMap.stripWithColours(text)
        return TextRun(lineId, 1, 1, row, 53f, display = s.display, colourSpans = s.spans, sourceMap = s.map, sourceStrokeIds = emptyList())
    }

    private fun line(
        id: String,
        n: Int,
        origin: LineOrigin?,
    ) = SpecLineModel(id, n, origin)

    private fun regionStroke(
        id: String,
        fromRow: Int,
        toRow: Int,
    ) = Stroke(
        id,
        Tool.IMAGE_REGION,
        3f,
        listOf(Point(100f, g.rowTop(fromRow).toFloat(), 1f, 0L), Point(700f, g.rowTop(toRow).toFloat(), 1f, 1L)),
    )

    private fun imageOp(
        id: String,
        vararg strokes: String,
    ) = PendingOp(
        "c1",
        EditOp(
            id,
            OpKind.IMAGE,
            strokeIds = strokes.toList(),
            rect = listOf(100f, 0f, 600f, 300f),
            region = RegionRef("reg-$id", "region.0.0.png", "L1"),
        ),
    )

    private fun applyOps(
        n: Notebook,
        markdown: String,
        lines: List<SpecLineModel>,
        ops: List<OpStatusModel>,
    ) = RunPlacer.apply(n, 3, markdown, SpecLayoutModel(lines, ops), measure, g)

    @Test
    fun applied_image_op_marks_region_and_keeps_ink() {
        val page =
            Page(
                listOf(regionStroke("r", 4, 9), stroke("in", 6)),
                false,
                text = listOf(run("L1", 2, "intro")),
                pendingOps = listOf(imageOp("op1", "r", "in")),
            )
        val md = "intro\n![image](images/reg-op1.png)"
        val p =
            applyOps(
                notebook(page),
                md,
                listOf(line("L1", 1, LineOrigin.Kept("L1")), line("L2", 2, LineOrigin.Op("op1"))),
                listOf(OpStatusModel("op1", true)),
            )
        val after = p.notebook.pages[0]
        assertThat(after.appliedRegions).containsExactly("r", "reg-op1")
        assertThat(after.pendingOps).isEmpty()
        // The ink inside the region is the picture: never consumed, still visible; the frame leaves the ink layer.
        assertThat(after.consumed).isEmpty()
        assertThat(after.visibleStrokes.map { it.id }).containsExactly("in")
        assertThat(after.strokes.map { it.id }).containsExactly("r", "in")
        // The image line is the region itself on the board: no text run for it.
        assertThat(after.text.map { it.lineId }).containsExactly("L1")
    }

    @Test
    fun rejected_image_op_leaves_the_region_pending_for_a_resend() {
        val page = Page(listOf(regionStroke("r", 4, 9), stroke("in", 6)), false, pendingOps = listOf(imageOp("op1", "r", "in")))
        val p = applyOps(notebook(page), "text", listOf(line("L1", 1, null)), listOf(OpStatusModel("op1", false, "unclear")))
        val after = p.notebook.pages[0]
        assertThat(after.appliedRegions).isEmpty()
        assertThat(after.pendingOps).isEmpty()
        assertThat(after.visibleStrokes.map { it.id }).containsExactly("r", "in")
        assertThat(ImageRegions.unsent(after).map { it.id }).containsExactly("r")
    }

    @Test
    fun ink_of_an_applied_region_stays_out_of_segments() {
        val page = Page(listOf(regionStroke("r", 4, 9), stroke("in", 6), stroke("w", 12)), true, appliedRegions = mapOf("r" to "reg-op1"))
        assertThat(ImageRegions.excluded(page)).containsExactly("in")
        assertThat(ImageRegions.unsent(page)).isEmpty()
        val segments = Segmenter.segment(page, g, 0, ImageRegions.excluded(page)).segments
        assertThat(segments.flatMap { it.strokeIds }).containsExactly("w")
    }

    private fun appliedPlacement(): Notebook {
        val page =
            Page(
                listOf(regionStroke("r", 4, 9), stroke("in", 6)),
                false,
                text = listOf(run("L1", 2, "intro")),
                pendingOps = listOf(imageOp("op1", "r", "in")),
            )
        return applyOps(
            notebook(page),
            "intro\n![image](images/reg-op1.png)",
            listOf(line("L1", 1, LineOrigin.Kept("L1")), line("L2", 2, LineOrigin.Op("op1"))),
            listOf(OpStatusModel("op1", true)),
        ).notebook
    }

    @Test
    fun revert_without_image_returns_region_to_pending() {
        val applied = appliedPlacement()
        val p =
            RunPlacer.apply(
                applied,
                4,
                "intro",
                SpecLayoutModel(listOf(line("L1", 1, LineOrigin.Kept("L1"))), emptyList(), revertedFrom = 2),
                measure,
                g,
            )
        val after = p.notebook.pages[0]
        assertThat(after.appliedRegions).isEmpty()
        assertThat(after.visibleStrokes.map { it.id }).containsExactly("r", "in")
        assertThat(ImageRegions.unsent(after).map { it.id }).containsExactly("r")
        assertThat(after.changedSinceSend).isTrue()
    }

    @Test
    fun later_version_without_image_returns_region_to_pending() {
        val p =
            RunPlacer.apply(
                appliedPlacement(),
                4,
                "intro",
                SpecLayoutModel(listOf(line("L1", 1, LineOrigin.Kept("L1"))), emptyList()),
                measure,
                g,
            )
        assertThat(p.notebook.pages[0].appliedRegions).isEmpty()
    }

    @Test
    fun later_version_still_referencing_the_image_keeps_it_applied() {
        val p =
            RunPlacer.apply(
                appliedPlacement(),
                4,
                "intro\nmore\n![image](images/reg-op1.png)",
                SpecLayoutModel(
                    listOf(line("L1", 1, LineOrigin.Kept("L1")), line("L3", 2, null), line("L2", 3, null)),
                    emptyList(),
                ),
                measure,
                g,
            )
        assertThat(p.notebook.pages[0].appliedRegions).hasSize(1)
    }
}
