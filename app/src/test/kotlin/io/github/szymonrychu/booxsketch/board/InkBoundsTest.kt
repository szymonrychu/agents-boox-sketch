package io.github.szymonrychu.booxsketch.board

import com.google.common.truth.Truth.assertThat
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
}
