package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ShapeGeometryTest {
    private fun drag(
        tool: Tool,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: PenColor = PenColor.RED,
    ) = Stroke(
        "d",
        tool,
        3f,
        listOf(Point(x0, y0, 0.4f, 5L), Point((x0 + x1) / 2, (y0 + y1) / 2, 0.5f, 7L), Point(x1, y1, 0.6f, 9L)),
        color,
    )

    private fun rect(
        id: String,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) = Stroke(id, Tool.RECTANGLE, 3f, listOf(Point(l, t, 1f, 0L), Point(r, b, 1f, 1L)))

    @Test
    fun rect_anchors_are_corners_then_midpoints() {
        val a = rectAnchors(RectF(0f, 0f, 100f, 50f))
        assertThat(a)
            .containsExactly(
                Vec(0f, 0f),
                Vec(100f, 0f),
                Vec(100f, 50f),
                Vec(0f, 50f),
                Vec(50f, 0f),
                Vec(100f, 25f),
                Vec(50f, 50f),
                Vec(0f, 25f),
            ).inOrder()
    }

    @Test
    fun rectangle_is_normalised_and_not_snapped() {
        val other = rect("r", 0f, 0f, 100f, 100f)
        val s = snapShape(drag(Tool.RECTANGLE, 102f, 98f, 20f, 30f), listOf(other))!!
        assertThat(s.points).hasSize(2)
        assertThat(s.points[0].x).isEqualTo(20f)
        assertThat(s.points[0].y).isEqualTo(30f)
        assertThat(s.points[1].x).isEqualTo(102f)
        assertThat(s.points[1].y).isEqualTo(98f)
        assertThat(s.points.map { it.pressure }).containsExactly(1f, 1f)
        assertThat(s.id).isEqualTo("d")
        assertThat(s.width).isEqualTo(3f)
        assertThat(s.color).isEqualTo(PenColor.RED)
    }

    @Test
    fun arrow_tail_and_tip_snap_to_nearest_anchor_within_radius() {
        val a = rect("a", 0f, 0f, 100f, 100f)
        val b = rect("b", 300f, 0f, 400f, 100f)
        val s = snapShape(drag(Tool.ARROW, 105f, 3f, 296f, 4f), listOf(a, b))!!
        assertThat(s.points[0].x).isEqualTo(100f)
        assertThat(s.points[0].y).isEqualTo(0f)
        assertThat(s.points[1].x).isEqualTo(300f)
        assertThat(s.points[1].y).isEqualTo(0f)
        assertThat(s.points[0].tMs).isEqualTo(5L)
        assertThat(s.points[1].tMs).isEqualTo(9L)
        assertThat(s.points[0].pressure).isEqualTo(1f)
    }

    @Test
    fun arrow_end_outside_radius_stays_raw() {
        val a = rect("a", 0f, 0f, 100f, 100f)
        val s = snapShape(drag(Tool.ARROW, 200f, 200f, 300f, 300f), listOf(a))!!
        assertThat(s.points[0].x).isEqualTo(200f)
        assertThat(s.points[1].y).isEqualTo(300f)
    }

    @Test
    fun arrow_snaps_to_side_midpoint() {
        val a = rect("a", 0f, 0f, 100f, 100f)
        val s = snapShape(drag(Tool.ARROW, 55f, 130f, 300f, 300f), listOf(a))!!
        assertThat(s.points[0].x).isEqualTo(50f)
        assertThat(s.points[0].y).isEqualTo(100f)
    }

    @Test
    fun both_ends_on_same_anchor_tip_stays_raw() {
        val a = rect("a", 0f, 0f, 100f, 100f)
        val s = snapShape(drag(Tool.ARROW, 105f, 105f, 110f, 112f), listOf(a))!!
        assertThat(s.points[0].x).isEqualTo(100f)
        assertThat(s.points[0].y).isEqualTo(100f)
        assertThat(s.points[1].x).isEqualTo(110f)
        assertThat(s.points[1].y).isEqualTo(112f)
    }

    @Test
    fun arrow_ignores_non_rectangle_strokes() {
        val pen = Stroke("p", Tool.BALLPOINT, 2f, listOf(Point(0f, 0f, 1f, 0L), Point(100f, 100f, 1f, 1L)))
        val arrow = drag(Tool.ARROW, 1f, 1f, 200f, 200f)
        val s = snapShape(arrow, listOf(pen, arrow.copy(id = "other")))!!
        assertThat(s.points[0].x).isEqualTo(1f)
        assertThat(s.points[0].y).isEqualTo(1f)
    }

    @Test
    fun tiny_shape_returns_null() {
        assertThat(snapShape(drag(Tool.RECTANGLE, 0f, 0f, 5f, 7f), emptyList())).isNull()
        assertThat(snapShape(drag(Tool.ARROW, 0f, 0f, 7.9f, 0f), emptyList())).isNull()
        assertThat(snapShape(drag(Tool.ARROW, 0f, 0f, 8f, 0f), emptyList())).isNotNull()
    }

    @Test
    fun non_shape_tool_returns_null() {
        assertThat(snapShape(drag(Tool.BALLPOINT, 0f, 0f, 100f, 100f), emptyList())).isNull()
    }

    @Test
    fun arrow_head_wings_symmetric_and_sized() {
        val tail = Vec(0f, 0f)
        val tip = Vec(100f, 0f)
        val (w1, w2) = arrowHead(tail, tip, 2f)
        assertThat(hypot(w1.x - tip.x, w1.y - tip.y)).isWithin(0.01f).of(16f)
        assertThat(hypot(w2.x - tip.x, w2.y - tip.y)).isWithin(0.01f).of(16f)
        assertThat(w1.x).isWithin(0.001f).of(w2.x)
        assertThat(w1.y).isWithin(0.001f).of(-w2.y)
        assertThat(w1.x).isLessThan(tip.x)
        assertThat(abs(w1.y)).isWithin(0.01f).of(16f * kotlin.math.sin(Math.toRadians(28.0)).toFloat())
        val big = arrowHead(tail, tip, 10f)
        assertThat(hypot(big.first.x - tip.x, big.first.y - tip.y)).isWithin(0.01f).of(40f)
    }
}
