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

class RunPlacerDiagramTest {
    private val g = NotebookGeometry(1404, 1872)
    private val canvas = Canvas(1404, 1872, 300f)
    private val measure = TextMeasure { text, scale -> text.length * 20f * scale }

    private fun stroke(
        id: String,
        row: Int,
        x0: Float,
        x1: Float,
    ): Stroke {
        val y = g.rowTop(row) + 70f
        return Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(x0, y, 0.5f, 0L), Point(x1, y, 0.5f, 10L)))
    }

    private fun pending(
        segmentId: String,
        row: Int,
        vararg strokeIds: String,
        x0: Float,
    ) = PendingSegment("c1", segmentId, row, x0, 300f, strokeIds.toList(), 0)

    private fun notebook(vararg pages: Page) = Notebook(canvas, pages.toList(), 0)

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
    ) = RunPlacer.apply(n, version, markdown, SpecLayoutModel(lines.toList(), revertedFrom = revertedFrom), measure, g, null)

    private fun shape(
        id: String,
        tool: Tool,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) = Stroke(id, tool, 2f, listOf(Point(x0, y0, 0.5f, 0L), Point(x1, y1, 0.5f, 10L)))

    /** Two boxes (A, B) with a label stroke each and an arrow between them, all on rows 4-5, plus one far box. */
    private fun diagramPage(): Page {
        val y = g.rowTop(4).toFloat()
        return Page(
            listOf(
                shape("boxA", Tool.RECTANGLE, 50f, y, 350f, y + 160f),
                shape("boxB", Tool.RECTANGLE, 650f, y, 950f, y + 160f),
                shape("arrow", Tool.ARROW, 360f, y + 80f, 640f, y + 80f),
                stroke("la", 4, 100f, 300f),
                stroke("lb", 4, 700f, 900f),
                shape("far", Tool.RECTANGLE, 50f, g.rowTop(12).toFloat(), 350f, g.rowTop(12) + 160f),
            ),
            false,
            pendingSegments = listOf(pending("p0-r4-0", 4, "la", x0 = 100f), pending("p0-r4-1", 4, "lb", x0 = 700f)),
        )
    }

    private val diagramMd = "intro\n```mermaid\nflowchart LR\n    A[\"Test\"] --> B[\"[[?text|test]]\"]\n```"

    private fun diagramLines() =
        arrayOf(
            line("L1", 1, LineOrigin.Kept("L1")),
            line("L2", 2, LineOrigin.Segments(listOf("p0-r4-0", "p0-r4-1"))),
            line("L3", 3, null),
            line("L4", 4, null),
            line("L5", 5, null),
        )

    @Test
    fun fenced_diagram_with_origins_places_marker_and_consumes_box_ink() {
        val n = notebook(diagramPage())
        val p = apply(n, diagramMd, *diagramLines())
        val page = p.notebook.pages[0]
        val marker = page.text.single { it.lineId == "L2" }
        assertThat(marker.display).isEqualTo("Diagram: Test -> text (open in Documents)")
        assertThat(marker.row).isEqualTo(4)
        assertThat(marker.sourceStrokeIds).containsExactly("la", "lb", "boxA", "boxB", "arrow")
        assertThat(page.consumed).containsExactly("la", "lb", "boxA", "boxB", "arrow")
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("far")
        assertThat(page.pendingSegments).isEmpty()
        // The marker survives the next version that keeps the line without origins.
        val again = apply(p.notebook, diagramMd, line("L1", 1, LineOrigin.Kept("L1")), line("L2", 2, LineOrigin.Kept("L2")), version = 3)
        assertThat(
            again.notebook.pages[0]
                .text
                .single { it.lineId == "L2" }
                .display,
        ).startsWith("Diagram: Test -> text")
        assertThat(
            again.notebook.pages[0]
                .visibleStrokes
                .map { it.id },
        ).containsExactly("far")
    }

    @Test
    fun revert_restores_diagram_ink() {
        val placed = apply(notebook(diagramPage()), diagramMd, *diagramLines()).notebook
        val back = apply(placed, "intro", line("L1", 1, LineOrigin.Kept("L1")), version = 4, revertedFrom = 1)
        val page = back.notebook.pages[0]
        assertThat(page.text.map { it.lineId }).containsExactly("L1")
        assertThat(page.consumed).isEmpty()
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("boxA", "boxB", "arrow", "la", "lb", "far")
        assertThat(page.changedSinceSend).isTrue()
    }

    @Test
    fun diagram_marker_falls_back_to_node_ids() {
        val md = "```mermaid\ngraph TD\n  A --> B -->|yes| C[Done]\n```"
        val p =
            apply(
                notebook(diagramPage()),
                md,
                line("L2", 1, LineOrigin.Segments(listOf("p0-r4-0"))),
                line("L3", 2, null),
                line("L4", 3, null),
                line("L5", 4, null),
            )
        assertThat(
            p.notebook.pages[0]
                .text
                .single()
                .display,
        ).isEqualTo("Diagram: A -> B -> Done (open in Documents)")
    }
}
