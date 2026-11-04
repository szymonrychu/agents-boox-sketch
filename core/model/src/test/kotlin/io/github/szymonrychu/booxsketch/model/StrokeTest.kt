package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StrokeTest {
    @Test
    fun pen_color_wire_names_and_argb() {
        assertThat(PenColor.entries.map { it.wire })
            .containsExactly("black", "red", "blue", "green", "orange", "purple")
            .inOrder()
        assertThat(PenColor.entries.map { it.argb })
            .containsExactly(
                0xFF000000.toInt(),
                0xFFE00000.toInt(),
                0xFF0050E0.toInt(),
                0xFF00A040.toInt(),
                0xFFFF8000.toInt(),
                0xFF8020C0.toInt(),
            ).inOrder()
    }

    @Test
    fun shape_tools_are_shapes_and_pens_are_not() {
        assertThat(Tool.RECTANGLE.wire).isEqualTo("rectangle")
        assertThat(Tool.ARROW.wire).isEqualTo("arrow")
        assertThat(Tool.entries.filter { it.isShape }).containsExactly(Tool.RECTANGLE, Tool.ARROW)
        assertThat(Tool.entries.filterNot { it.isShape }).hasSize(6)
    }

    @Test
    fun stroke_color_defaults_to_black() {
        assertThat(Stroke("a", Tool.BRUSH, 2f, emptyList()).color).isEqualTo(PenColor.BLACK)
    }
}
