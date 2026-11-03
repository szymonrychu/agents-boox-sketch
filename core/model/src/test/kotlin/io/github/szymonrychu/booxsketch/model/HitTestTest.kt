package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitTestTest {
    private fun stroke(
        id: String,
        width: Float,
        vararg pts: Pair<Float, Float>,
    ) = Stroke(id, Tool.BALLPOINT, width, pts.mapIndexed { i, p -> Point(p.first, p.second, 1f, i * 10L) })

    @Test
    fun hit_on_segment_between_points() {
        val s = stroke("a", 2f, 0f to 0f, 100f to 0f)
        assertThat(hitStrokes(listOf(s), 50f, 3f, 5f)).containsExactly("a")
    }

    @Test
    fun miss_outside_width_tolerance() {
        val s = stroke("a", 2f, 0f to 0f, 100f to 0f)
        assertThat(hitStrokes(listOf(s), 50f, 7f, 5f)).isEmpty()
    }

    @Test
    fun single_point_stroke_hit() {
        val s = stroke("a", 4f, 10f to 10f)
        assertThat(hitStrokes(listOf(s), 14f, 10f, 2f)).containsExactly("a")
        assertThat(hitStrokes(listOf(s), 20f, 10f, 2f)).isEmpty()
    }
}
