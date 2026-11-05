package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotebookGeometryTest {
    private val g = NotebookGeometry(1404, 1872)

    @Test
    fun cell_is_six_millimetres_and_the_row_twice_that() {
        assertThat(g.cell).isEqualTo(53)
        assertThat(g.rowPitch).isEqualTo(106)
    }

    @Test
    fun top_margin_is_one_row_and_sixteen_rows_fit() {
        assertThat(g.topMargin).isEqualTo(106)
        assertThat(g.rowCount).isEqualTo(16)
        // 1872 - 106 - 16 * 106 leaves a 70 px foot.
        assertThat(1872 - g.rowLineY(15)).isEqualTo(70)
    }

    @Test
    fun a_row_line_closes_its_row_and_splits_into_two_bands() {
        assertThat(g.rowTop(0)).isEqualTo(106)
        assertThat(g.rowLineY(0)).isEqualTo(212)
        assertThat(g.rowLineY(15)).isEqualTo(1802)
        assertThat(g.annotationBandTop(0)).isEqualTo(106)
        assertThat(g.textBandTop(0)).isEqualTo(159)
        assertThat(g.textBandTop(0) - g.annotationBandTop(0)).isEqualTo(53)
        assertThat(g.rowLineY(0) - g.textBandTop(0)).isEqualTo(53)
    }

    @Test
    fun row_at_maps_a_y_to_its_row_or_null_outside_the_rows() {
        assertThat(g.rowAt(0f)).isNull()
        assertThat(g.rowAt(105.9f)).isNull()
        assertThat(g.rowAt(106f)).isEqualTo(0)
        assertThat(g.rowAt(211.9f)).isEqualTo(0)
        assertThat(g.rowAt(212f)).isEqualTo(1)
        assertThat(g.rowAt(1801.9f)).isEqualTo(15)
        assertThat(g.rowAt(1802f)).isNull()
        assertThat(g.rowAt(1871f)).isNull()
        assertThat(g.rowAt(-5f)).isNull()
    }

    @Test
    fun band_at_tells_annotation_from_text_half() {
        assertThat(g.bandAt(120f)).isEqualTo(NotebookGeometry.Band.ANNOTATION)
        assertThat(g.bandAt(158.9f)).isEqualTo(NotebookGeometry.Band.ANNOTATION)
        assertThat(g.bandAt(159f)).isEqualTo(NotebookGeometry.Band.TEXT)
        assertThat(g.bandAt(211f)).isEqualTo(NotebookGeometry.Band.TEXT)
        assertThat(g.bandAt(50f)).isNull()
    }

    @Test
    fun margin_is_the_right_thirty_percent() {
        assertThat(g.marginX).isEqualTo(983)
        assertThat(1404 - g.marginX).isEqualTo(421)
        assertThat(g.inMargin(982.9f)).isFalse()
        assertThat(g.inMargin(983f)).isTrue()
        assertThat(g.inMargin(1403f)).isTrue()
    }

    @Test
    fun text_column_runs_from_one_cell_to_the_margin() {
        assertThat(g.textLeft).isEqualTo(53)
        assertThat(g.inTextColumn(52.9f)).isFalse()
        assertThat(g.inTextColumn(53f)).isTrue()
        assertThat(g.inTextColumn(982.9f)).isTrue()
        assertThat(g.inTextColumn(983f)).isFalse()
    }

    @Test
    fun row_lines_in_a_y_range_are_found_by_index() {
        assertThat(g.rowLinesIn(0f, 211.9f)).isEmpty()
        assertThat(g.rowLinesIn(0f, 212f)).containsExactly(0)
        assertThat(g.rowLinesIn(200f, 330f)).containsExactly(0, 1).inOrder()
        assertThat(g.rowLinesIn(1800f, 1872f)).containsExactly(15)
        assertThat(g.rowLinesIn(-50f, 5000f).toList()).hasSize(16)
        assertThat(g.rowLinesIn(300f, 200f)).isEmpty()
    }

    @Test
    fun dots_sit_at_cell_intersections_inside_the_page() {
        assertThat(g.dotColumnsIn(0f, 110f)).containsExactly(1, 2).inOrder()
        assertThat(g.dotColumnsIn(53f, 53f)).containsExactly(1)
        assertThat(g.dotColumnsIn(54f, 105f)).isEmpty()
        assertThat(g.dotRowsIn(0f, 1872f).last()).isEqualTo(35)
        assertThat(g.dotColumnsIn(0f, 1404f).last()).isEqualTo(26)
    }

    @Test
    fun a_dot_on_a_row_line_in_the_text_column_is_covered_by_the_line() {
        // Cell row 4 is y = 212, the first row line.
        assertThat(g.dotCovered(column = 2, row = 4)).isTrue()
        assertThat(g.dotCovered(column = 2, row = 5)).isFalse()
        // The margin has dots only.
        assertThat(g.dotCovered(column = 20, row = 4)).isFalse()
        // Above the first row line there is no line.
        assertThat(g.dotCovered(column = 2, row = 2)).isFalse()
        assertThat(g.dotCovered(column = 2, row = 36)).isFalse()
    }

    @Test
    fun other_canvas_sizes_keep_the_same_pitch() {
        val small = NotebookGeometry(100, 300)
        assertThat(small.cell).isEqualTo(53)
        assertThat(small.rowCount).isEqualTo(1)
        assertThat(small.marginX).isEqualTo(70)
        val tiny = NotebookGeometry(10, 10)
        assertThat(tiny.rowCount).isEqualTo(0)
        assertThat(tiny.rowAt(5f)).isNull()
    }
}
