package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import org.junit.Test

class WordBoxesTest {
    private val g = NotebookGeometry(1404, 1872)

    /** 20 px per char at scale 1. */
    private val measure = TextMeasure { text, scale -> text.length * 20f * scale }

    private fun run(
        text: String,
        row: Int = 3,
        x0: Float = 53f,
        scale: Float = 1f,
        wrapRows: Int = 1,
        column: Float = 0f,
    ): TextRun {
        val s = SourceMap.stripWithColours(text)
        return TextRun(
            "L1",
            1,
            1,
            row,
            x0,
            wrapRows = wrapRows,
            scale = scale,
            display = s.display,
            colourSpans = s.spans,
            sourceMap = s.map,
            sourceStrokeIds = emptyList(),
            column = column,
        )
    }

    @Test
    fun boxes_follow_measured_widths() {
        val boxes = WordBoxes.of(run("buy fresh milk"), g, measure)
        assertThat(boxes)
            .containsExactly(
                WordBox(0, 3, 53f, 113f, 3),
                WordBox(4, 9, 133f, 233f, 3),
                WordBox(10, 14, 253f, 333f, 3),
            ).inOrder()
    }

    @Test
    fun heading_boxes_use_the_bold_width() {
        val bold =
            object : TextMeasure {
                override fun width(text: String, scale: Float) = text.length * 20f * scale

                override fun width(text: String, scale: Float, bold: Boolean) = text.length * (if (bold) 30f else 20f) * scale
            }
        val heading = run("buy milk").copy(heading = true)
        assertThat(WordBoxes.of(heading, g, bold))
            .containsExactly(WordBox(0, 3, 53f, 143f, 3), WordBox(4, 8, 173f, 293f, 3))
            .inOrder()
    }

    @Test
    fun wrapped_run_boxes_on_second_row() {
        val boxes = WordBoxes.of(run("alpha beta gamma delta", row = 5, wrapRows = 2, column = 240f), g, measure)
        assertThat(boxes.map { it.row }).containsExactly(5, 5, 6, 6).inOrder()
        assertThat(boxes[2]).isEqualTo(WordBox(11, 16, 53f, 153f, 6))
        assertThat(boxes[3]).isEqualTo(WordBox(17, 22, 173f, 273f, 6))
    }

    @Test
    fun overlap_expands_to_whole_words() {
        val boxes = WordBoxes.of(run("buy fresh milk"), g, measure)
        assertThat(WordBoxes.overlapped(boxes, 150f, 260f)).isEqualTo(4 until 14)
        assertThat(WordBoxes.overlapped(boxes, 20f, 60f)).isEqualTo(0 until 3)
        // The gap between two words touches neither.
        assertThat(WordBoxes.overlapped(boxes, 115f, 130f)).isNull()
        assertThat(WordBoxes.overlapped(boxes, 400f, 500f)).isNull()
    }

    @Test
    fun nearest_boundary_between_word_centres() {
        val boxes = WordBoxes.of(run("buy fresh milk"), g, measure)
        // Centres: buy 83, fresh 183, milk 293.
        assertThat(WordBoxes.nearestBoundary(boxes, 60f)).isEqualTo(0)
        assertThat(WordBoxes.nearestBoundary(boxes, 100f)).isEqualTo(3)
        assertThat(WordBoxes.nearestBoundary(boxes, 200f)).isEqualTo(9)
        assertThat(WordBoxes.nearestBoundary(boxes, 900f)).isEqualTo(14)
    }

    @Test
    fun scaled_run_boxes_scaled() {
        val boxes = WordBoxes.of(run("buy fresh milk", scale = 0.85f), g, measure)
        assertThat(boxes[1].left).isWithin(0.01f).of(53f + 4 * 17f)
        assertThat(boxes[1].right).isWithin(0.01f).of(53f + 9 * 17f)
    }

    @Test
    fun boxes_skip_markup_and_keep_display_offsets() {
        val boxes = WordBoxes.of(run("**buy** milk"), g, measure)
        assertThat(boxes.map { it.from to it.to }).containsExactly(0 to 3, 4 to 8).inOrder()
    }
}
