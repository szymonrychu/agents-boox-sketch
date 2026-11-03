package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportGeometryTest {
    private val canvas = Canvas(2000, 3000, 300f)

    @Test
    fun crop_adds_margin_clamped_to_canvas() {
        val plan = planExport(RectF(10f, 500f, 400f, 900f), canvas, 1568)!!
        assertThat(plan.cropLeft).isEqualTo(0)
        assertThat(plan.cropTop).isEqualTo(476)
        assertThat(plan.cropW).isEqualTo(424)
        assertThat(plan.cropH).isEqualTo(448)
        val edge = planExport(RectF(1900f, 2900f, 1990f, 2990f), canvas, 1568)!!
        assertThat(edge.cropLeft + edge.cropW).isEqualTo(2000)
        assertThat(edge.cropTop + edge.cropH).isEqualTo(3000)
    }

    @Test
    fun long_edge_scaled_to_cap_1568() {
        val plan = planExport(RectF(0f, 0f, 2000f, 3000f), canvas, 1568)!!
        assertThat(maxOf(plan.outW, plan.outH)).isEqualTo(1568)
        assertThat(plan.scale).isWithin(1e-4f).of(1568f / 3000f)
    }

    @Test
    fun small_drawing_padded_to_min_edge_200() {
        val plan = planExport(RectF(500f, 500f, 520f, 520f), canvas, 1568, margin = 0)!!
        assertThat(plan.cropW).isEqualTo(20)
        assertThat(plan.outW).isEqualTo(200)
        assertThat(plan.outH).isEqualTo(200)
    }

    @Test
    fun thin_line_padded_short_edge() {
        val plan = planExport(RectF(100f, 800f, 1500f, 800f), canvas, 1568, margin = 0)!!
        assertThat(plan.outW).isEqualTo(1400)
        assertThat(plan.outH).isEqualTo(200)
    }

    @Test
    fun no_strokes_returns_null() {
        assertThat(planExport(null, canvas, 1568)).isNull()
    }

    @Test
    fun never_upscales_beyond_canvas_when_under_cap() {
        val plan = planExport(RectF(100f, 100f, 600f, 700f), canvas, 1568)!!
        assertThat(plan.scale).isEqualTo(1f)
        assertThat(plan.outW).isEqualTo(plan.cropW)
        assertThat(plan.outH).isEqualTo(plan.cropH)
    }
}
