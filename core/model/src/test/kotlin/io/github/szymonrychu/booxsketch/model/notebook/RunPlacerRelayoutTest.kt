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

class RunPlacerRelayoutTest {
    private val g = NotebookGeometry(1404, 1872)
    private val canvas = Canvas(1404, 1872, 300f)

    /** 20 px per char at scale 1: the text column from x 53 holds 46 chars. */
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
        strokes: List<String> = emptyList(),
    ): TextRun {
        val s = SourceMap.stripWithColours(text)
        return TextRun(
            lineId,
            1,
            sourceLine,
            row,
            53f,
            display = s.display,
            colourSpans = s.spans,
            sourceMap = s.map,
            sourceStrokeIds = strokes,
        )
    }

    private fun line(
        id: String,
        n: Int,
    ) = SpecLineModel(id, n, LineOrigin.Kept(id))

    private fun Notebook.runs(page: Int = 0) = pages[page].text

    @Test
    fun saved_runs_at_an_older_scale_are_laid_out_again_at_the_one_text_size() {
        // An older build shrank L1 to 0.85 (53 chars: 901 px, one row); at scale 1 it needs two rows and pushes L2 down.
        val long = "word ".repeat(10) + "end"
        val n =
            notebook(
                Page(
                    listOf(stroke("a", 2), stroke("b", 5)),
                    false,
                    text =
                        listOf(
                            run("L1", 2, long, strokes = listOf("a")).copy(scale = 0.85f, wrapRows = 1, column = 930f),
                            run("L2", 3, "short", sourceLine = 2),
                        ),
                    consumed = setOf("a"),
                ),
            )
        assertThat(RunPlacer.needsRelayout(n)).isTrue()
        val p = RunPlacer.relayout(n, measure, g)
        val runs = p.notebook.runs().associateBy { it.lineId }
        assertThat(runs.values.map { it.scale }.toSet()).containsExactly(RunPlacer.TEXT_SCALE)
        assertThat(runs.getValue("L1").row).isEqualTo(2)
        assertThat(runs.getValue("L1").wrapRows).isEqualTo(2)
        assertThat(runs.getValue("L2").row).isEqualTo(4)
        assertThat(runs.getValue("L1").sourceStrokeIds).containsExactly("a")
        assertThat(p.notebook.pages[0].consumed).containsExactly("a")
        // The visible ink below moved down with the pushed row.
        assertThat(
            p.notebook.pages[0]
                .strokes
                .single { it.id == "b" }
                .points[0]
                .y,
        ).isEqualTo(g.rowTop(6) + 70f)
        assertThat(p.notebook.appliedSpecVersion).isEqualTo(n.appliedSpecVersion)
        assertThat(p.changedRows[0]!!.contains(2)).isTrue()
        assertThat(RunPlacer.needsRelayout(p.notebook)).isFalse()
        // Once at the one size, a second pass changes nothing.
        assertThat(RunPlacer.relayout(p.notebook, measure, g).notebook).isEqualTo(p.notebook)
    }

    @Test
    fun a_revert_after_the_relayout_still_renders_at_the_one_text_size() {
        val long = "word ".repeat(10) + "end"
        val n =
            notebook(
                Page(
                    emptyList(),
                    false,
                    text = listOf(run("L1", 2, long).copy(scale = 0.85f), run("L2", 3, "short", sourceLine = 2).copy(scale = 1f)),
                ),
            )
        val relaid = RunPlacer.relayout(n, measure, g).notebook
        val layout = SpecLayoutModel(listOf(line("L1", 1), line("L2", 2)), revertedFrom = 1)
        val p = RunPlacer.apply(relaid, 4, "$long\nshort", layout, measure, g)
        assertThat(
            p.notebook
                .runs()
                .map { it.scale }
                .toSet(),
        ).containsExactly(RunPlacer.TEXT_SCALE)
        assertThat(
            p.notebook
                .runs()
                .single { it.lineId == "L2" }
                .row,
        ).isEqualTo(4)
    }
}
