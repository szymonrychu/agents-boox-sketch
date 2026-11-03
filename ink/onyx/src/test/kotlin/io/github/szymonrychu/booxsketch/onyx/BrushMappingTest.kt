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
        assertThat(Tool.entries).hasSize(6)
        for (tool in Tool.entries) {
            assertThat(BrushMapping.style(tool)).isEqualTo(expected.getValue(tool))
        }
        assertThat(Tool.entries.map { BrushMapping.style(it) }.toSet()).hasSize(6)
    }

    @Test
    fun width_is_passed_through_and_never_below_one_pixel() {
        assertThat(BrushMapping.width(5f)).isEqualTo(5f)
        assertThat(BrushMapping.width(0f)).isEqualTo(1f)
    }
}
