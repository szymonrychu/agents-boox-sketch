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

class SegmenterTest {
    private val g = NotebookGeometry(1404, 1872)

    /** A freehand stroke from (x0, y) to (x1, y) in row [row]'s text band unless [y] is given. */
    private fun stroke(
        id: String,
        x0: Float,
        x1: Float,
        row: Int = 3,
        y: Float = g.rowTop(row) + 70f,
        tool: Tool = Tool.BALLPOINT,
        y1: Float = y,
    ) = Stroke(id, tool, 2f, listOf(Point(x0, y, 0.5f, 0L), Point(x1, y1, 0.5f, 10L)))

    private fun page(
        vararg s: Stroke,
        consumed: Set<String> = emptySet(),
        pending: List<PendingSegment> = emptyList(),
    ) = Page(s.toList(), true, consumed = consumed, pendingSegments = pending)

    private fun segment(
        p: Page,
        exclude: Set<String> = emptySet(),
        index: Int = 0,
    ) = Segmenter.segment(p, g, index, exclude)

    @Test
    fun one_row_one_segment() {
        val r = segment(page(stroke("a", 100f, 200f), stroke("b", 250f, 400f)))
        assertThat(r.segments).hasSize(1)
        val s = r.segments[0]
        assertThat(s.id).isEqualTo("p0-r3-0")
        assertThat(s.row).isEqualTo(3)
        assertThat(s.x0).isEqualTo(100f)
        assertThat(s.x1).isEqualTo(400f)
        assertThat(s.strokeIds).containsExactly("a", "b").inOrder()
        assertThat(r.drawing).isEmpty()
    }

    @Test
    fun gap_over_two_cells_splits() {
        val r = segment(page(stroke("a", 100f, 200f), stroke("b", 200f + 107f, 500f), stroke("c", 520f, 600f)))
        assertThat(r.segments.map { it.id }).containsExactly("p0-r3-0", "p0-r3-1").inOrder()
        assertThat(r.segments[0].strokeIds).containsExactly("a")
        assertThat(r.segments[1].strokeIds).containsExactly("b", "c")
    }

    @Test
    fun gap_of_exactly_two_cells_does_not_split() {
        val r = segment(page(stroke("a", 100f, 200f), stroke("b", 306f, 400f)))
        assertThat(r.segments).hasSize(1)
    }

    @Test
    fun strokes_are_ordered_by_x_and_rows_get_their_own_numbering() {
        val r =
            segment(page(stroke("late", 250f, 300f, row = 5), stroke("early", 100f, 200f, row = 5), stroke("other", 100f, 200f, row = 2)))
        assertThat(r.segments.map { it.id }).containsExactly("p0-r2-0", "p0-r5-0").inOrder()
        assertThat(r.segments[1].strokeIds).containsExactly("early", "late").inOrder()
    }

    @Test
    fun page_index_is_in_the_id() {
        assertThat(segment(page(stroke("a", 100f, 200f)), index = 4).segments[0].id).isEqualTo("p4-r3-0")
    }

    @Test
    fun multi_row_stroke_is_drawing() {
        val tall = stroke("t", 100f, 200f, y = g.rowTop(3) + 10f, y1 = g.rowTop(3) + 10f + 1.6f * g.rowPitch)
        val r = segment(page(tall, stroke("a", 100f, 200f, row = 8)))
        assertThat(r.drawing).containsExactly("t")
        assertThat(r.segments.flatMap { it.strokeIds }).containsExactly("a")
    }

    @Test
    fun margin_strokes_excluded() {
        val r = segment(page(stroke("m", 1100f, 1300f), stroke("edge", 900f, 970f)))
        assertThat(r.segments.flatMap { it.strokeIds }).containsExactly("edge").inOrder()
        assertThat(r.drawing).isEmpty()
    }

    @Test
    fun consumed_and_pending_strokes_excluded() {
        val pending = PendingSegment("c", "p0-r3-0", 3, 100f, 200f, listOf("p"))
        val p =
            page(
                stroke("a", 100f, 200f),
                stroke("c", 100f, 200f, row = 6),
                stroke("p", 100f, 200f, row = 7),
                consumed = setOf("c"),
                pending = listOf(pending),
            )
        val r = segment(p)
        assertThat(r.segments.flatMap { it.strokeIds }).containsExactly("a")
        assertThat(r.drawing).isEmpty()
    }

    @Test
    fun excluded_set_is_skipped() {
        val r = segment(page(stroke("a", 100f, 200f), stroke("op", 100f, 200f, row = 6)), exclude = setOf("op"))
        assertThat(r.segments.flatMap { it.strokeIds }).containsExactly("a")
        assertThat(r.drawing).isEmpty()
    }

    @Test
    fun shapes_are_drawing() {
        val rect = stroke("r", 100f, 300f, tool = Tool.RECTANGLE, y1 = g.rowTop(3) + 100f)
        val arrow = stroke("w", 100f, 300f, tool = Tool.ARROW)
        val r = segment(page(rect, arrow, stroke("a", 100f, 200f, row = 8)))
        assertThat(r.drawing).containsExactly("r", "w")
        assertThat(r.segments.flatMap { it.strokeIds }).containsExactly("a")
    }

    @Test
    fun top_margin_stroke_is_drawing() {
        val r = segment(page(stroke("title", 100f, 400f, y = 40f)))
        assertThat(r.drawing).containsExactly("title")
        assertThat(r.segments).isEmpty()
    }

    @Test
    fun stroke_below_the_last_row_is_drawing() {
        val r = segment(page(stroke("foot", 100f, 400f, y = 1860f)))
        assertThat(r.drawing).containsExactly("foot")
    }

    @Test
    fun undo_after_apply_keeps_consumed_strokes_out_of_segments() {
        val a = stroke("a", 100f, 200f)
        val b = stroke("b", 100f, 200f, row = 6)
        val undo = UndoStack()
        var n = Notebook(Canvas(1404, 1872, 300f), listOf(Page(emptyList(), false)), 0)
        n = undo.apply(n, Command.AddStroke(a))
        n = undo.apply(n, Command.AddStroke(b))
        // a spec version used stroke "a": it is consumed and the page holds text
        val run =
            SourceMap.stripWithColours("hi").let {
                TextRun("L1", 1, 1, 3, 53f, display = it.display, colourSpans = it.spans, sourceMap = it.map, sourceStrokeIds = listOf("a"))
            }
        n = n.copy(pages = listOf(n.pages[0].copy(consumed = setOf("a"), text = listOf(run))))
        // erase "a" (it is hidden, so only via undo history), undo the erase
        n = undo.apply(n, Command.EraseStrokes(setOf("a")))
        n = undo.undo(n)
        val page = n.pages[0]
        assertThat(page.strokes.map { it.id }).containsExactly("a", "b")
        assertThat(page.consumed).containsExactly("a")
        assertThat(page.text).hasSize(1)
        assertThat(Segmenter.segment(page, g, 0).segments.flatMap { it.strokeIds }).containsExactly("b")
    }
}
