package io.github.szymonrychu.booxsketch.board

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.Vec
import io.github.szymonrychu.booxsketch.model.arrowHead
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InkBoundsTest {
    private val g = BoardGeometry(200, 200, PageCanvas(200, 200, 1f))

    private fun shape(
        tool: Tool,
        vararg xy: Float,
    ) = Stroke("s", tool, 4f, xy.toList().chunked(2).map { (x, y) -> Point(x, y, 0.5f, 0L) })

    @Test
    fun a_rectangle_is_bounded_by_its_corners_plus_the_stroke_width() {
        val b = g.inkBounds(shape(Tool.RECTANGLE, 20f, 30f, 80f, 90f))!!
        assertThat(b.left).isAtMost(18f)
        assertThat(b.top).isAtMost(28f)
        assertThat(b.right).isAtLeast(82f)
        assertThat(b.bottom).isAtLeast(92f)
    }

    @Test
    fun an_arrow_bound_covers_its_head_which_reaches_past_the_tip() {
        val s = shape(Tool.ARROW, 20f, 20f, 100f, 20f)
        val b = g.inkBounds(s)!!
        val (w1, w2) = arrowHead(Vec(20f, 20f), Vec(100f, 20f), s.width)
        for (w in listOf(w1, w2)) {
            assertThat(b.contains(w.x, w.y)).isTrue()
        }
        assertThat(b.contains(20f, 20f)).isTrue()
        assertThat(b.contains(100f, 20f)).isTrue()
    }

    @Test
    fun a_stroke_without_points_has_no_bounds() {
        assertThat(g.inkBounds(Stroke("e", Tool.BALLPOINT, 4f, emptyList()))).isNull()
    }

    @Test
    fun charcoal_bounds_cover_the_texture_grain_past_the_line() {
        for (tool in listOf(Tool.CHARCOAL, Tool.CHARCOAL_V2)) {
            val b = g.inkBounds(shape(tool, 100f, 100f, 120f, 100f))!!
            // 8x the width across: the tilt-scaled texture stamp reaches 4 widths from the line.
            assertThat(b.top).isAtMost(100f - 4f * 4f)
            assertThat(b.left).isAtMost(100f - 4f * 4f)
            assertThat(b.right).isAtLeast(120f + 4f * 4f)
        }
        assertThat(g.inkBounds(shape(Tool.FOUNTAIN, 100f, 100f, 120f, 100f))!!.top).isGreaterThan(100f - 4f * 4f)
    }

    /** Every edge holds the outline's extreme point plus the [pad] and the rect is not empty. */
    private fun assertBounds(
        tool: Tool,
        xy: FloatArray,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val b = g.inkBounds(shape(tool, *xy))!!
        val what = "$tool ${xy.toList()}: $b"
        assertWithMessage(what).that(b.isEmpty).isFalse()
        assertWithMessage(what).that(b.left).isAtMost(left - 2f)
        assertWithMessage(what).that(b.top).isAtMost(top - 2f)
        assertWithMessage(what).that(b.right).isAtLeast(right + 2f)
        assertWithMessage(what).that(b.bottom).isAtLeast(bottom + 2f)
    }

    @Test
    fun a_right_to_left_stroke_is_bounded_on_every_side() {
        // The device InkDiag lines had right = Int.MIN_VALUE for exactly these down-left diagonals.
        for (tool in Tool.entries) {
            assertBounds(tool, floatArrayOf(180f, 20f, 120f, 90f, 40f, 170f), 40f, 20f, 180f, 170f)
        }
    }

    @Test
    fun a_bottom_to_top_stroke_is_bounded_on_every_side() {
        for (tool in Tool.entries) {
            assertBounds(tool, floatArrayOf(40f, 170f, 120f, 90f, 180f, 20f), 40f, 20f, 180f, 170f)
        }
    }

    @Test
    fun a_dot_has_a_non_empty_bound_around_its_point() {
        for (tool in Tool.entries.filterNot { it.isShape }) {
            assertBounds(tool, floatArrayOf(100f, 100f), 100f, 100f, 100f, 100f)
            assertThat(g.inkBounds(shape(tool, 100f, 100f))!!.contains(100f, 100f)).isTrue()
        }
    }

    @Test
    fun a_stroke_starting_at_its_rightmost_and_lowest_point_keeps_that_point() {
        for (tool in Tool.entries.filterNot { it.isShape }) {
            assertBounds(tool, floatArrayOf(190f, 150f, 150f, 20f, 180f, 60f), 150f, 20f, 190f, 150f)
        }
    }
}
