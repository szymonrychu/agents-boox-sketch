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

    private fun shape(
        tool: Tool,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) = Stroke("s", tool, 2f, listOf(Point(x0, y0, 1f, 0L), Point(x1, y1, 1f, 1L)))

    @Test
    fun rectangle_hit_on_outline_not_inside() {
        val r = shape(Tool.RECTANGLE, 0f, 0f, 100f, 100f)
        assertThat(hitStrokes(listOf(r), 50f, 2f, 5f)).containsExactly("s")
        assertThat(hitStrokes(listOf(r), 100f, 50f, 5f)).containsExactly("s")
        assertThat(hitStrokes(listOf(r), 50f, 50f, 5f)).isEmpty()
    }

    @Test
    fun arrow_hit_on_shaft_and_head() {
        val a = shape(Tool.ARROW, 0f, 0f, 100f, 0f)
        assertThat(hitStrokes(listOf(a), 40f, 2f, 5f)).containsExactly("s")
        val (wing, _) = arrowHead(Vec(0f, 0f), Vec(100f, 0f), 2f)
        assertThat(hitStrokes(listOf(a), wing.x, wing.y, 3f)).containsExactly("s")
        assertThat(hitStrokes(listOf(a), 40f, 30f, 5f)).isEmpty()
    }

    @Test
    fun freehand_hit_unchanged() {
        val s = stroke("a", 2f, 0f to 0f, 100f to 0f, 100f to 100f)
        assertThat(hitStrokes(listOf(s), 100f, 50f, 3f)).containsExactly("a")
        assertThat(hitStrokes(listOf(s), 50f, 50f, 3f)).isEmpty()
    }
}
