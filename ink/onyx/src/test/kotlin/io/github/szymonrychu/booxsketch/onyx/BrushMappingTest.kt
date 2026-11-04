package io.github.szymonrychu.booxsketch.onyx

import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.pen.TouchHelper
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test

class BrushMappingTest {
    @Test
    fun every_tool_maps_to_an_onyx_style() {
        val expected =
            mapOf(
                Tool.FOUNTAIN to TouchHelper.STROKE_STYLE_FOUNTAIN,
                Tool.BRUSH to TouchHelper.STROKE_STYLE_NEO_BRUSH,
                Tool.BALLPOINT to TouchHelper.STROKE_STYLE_PENCIL,
                Tool.CHARCOAL to TouchHelper.STROKE_STYLE_CHARCOAL,
                Tool.CHARCOAL_V2 to TouchHelper.STROKE_STYLE_CHARCOAL_V2,
                Tool.MARKER to TouchHelper.STROKE_STYLE_MARKER,
            )
        val pens = Tool.entries.filterNot { it.isShape }
        assertThat(pens).hasSize(6)
        for (tool in pens) {
            assertThat(BrushMapping.style(tool)).isEqualTo(expected.getValue(tool))
        }
        assertThat(pens.map { BrushMapping.style(it) }.toSet()).hasSize(6)
    }

    @Test
    fun shape_tools_map_to_pencil() {
        assertThat(BrushMapping.style(Tool.RECTANGLE)).isEqualTo(TouchHelper.STROKE_STYLE_PENCIL)
        assertThat(BrushMapping.style(Tool.ARROW)).isEqualTo(TouchHelper.STROKE_STYLE_PENCIL)
    }

    @Test
    fun width_follows_the_redraw_factor_of_the_tool_and_is_never_below_one_pixel() {
        assertThat(BrushMapping.width(Tool.BALLPOINT, 5f)).isEqualTo(5f)
        assertThat(BrushMapping.width(Tool.FOUNTAIN, 5f)).isEqualTo(5f)
        assertThat(BrushMapping.width(Tool.CHARCOAL_V2, 5f)).isEqualTo(6f)
        assertThat(BrushMapping.width(Tool.MARKER, 5f)).isEqualTo(15f)
        assertThat(BrushMapping.width(Tool.BALLPOINT, 0f)).isEqualTo(1f)
    }
}
