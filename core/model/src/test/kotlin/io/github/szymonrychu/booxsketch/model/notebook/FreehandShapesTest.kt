package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.pen
import org.junit.Test

class FreehandShapesTest {
    private fun fixture(name: String) = NotebookFixtures.freehand(name)

    private val loops =
        listOf(
            "loop_closed_circle",
            "loop_gap_open",
            "loop_overshoot_crossing",
            "loop_wobbly_oval",
            "loop_big_round",
            "loop_small_ninety_px",
            "loop_lead_in_overshoot",
            "loop_long_lead_in",
            "loop_overshoot_outside",
            "loop_one_point_eight_cell",
        )
    private val arrows =
        listOf(
            "arrow_hook_right",
            "arrow_hook_diagonal",
            "arrow_two_strokes_v",
            "arrow_chevron_one_stroke",
            "arrow_shaft_then_v_up",
            "arrow_vertical_hook_head_two_strokes",
        )
    private val negatives =
        listOf("neg_letter_o", "neg_letter_d", "neg_digit_zero", "neg_underline", "neg_bracket", "neg_long_word")
    private val letterNegatives =
        listOf(
            "neg_letter_c",
            "neg_letter_u",
            "neg_letter_q",
            "neg_letter_e",
            "neg_digit_eight",
            "neg_check_mark",
            "neg_line_end_flick",
            "neg_ring_one_point_four_cell",
        )

    @Test
    fun traced_loops_recognised() {
        val cell = NotebookGeometry.CELL
        for (name in loops) {
            val stroke = fixture(name).single()
            val loop = FreehandShapes.loop(stroke)
            assertWithMessage(name).that(loop).isNotNull()
            loop!!
            assertThat(loop.strokeId).isEqualTo(stroke.id)
            assertThat(loop.polygon.size).isAtLeast(8)
            assertThat(loop.bounds.r - loop.bounds.l).isAtLeast(cell.toFloat())
            assertThat(loop.bounds.b - loop.bounds.t).isAtLeast(cell.toFloat())
        }
    }

    @Test
    fun letters_are_not_loops() {
        for (name in negatives) {
            for (stroke in fixture(name)) {
                assertThat(FreehandShapes.loop(stroke)).isNull()
            }
        }
    }

    @Test
    fun arrows_are_not_loops() {
        for (name in arrows) {
            for (stroke in fixture(name)) {
                assertThat(FreehandShapes.loop(stroke)).isNull()
            }
        }
    }

    @Test
    fun shape_tool_strokes_are_not_freehand_loops() {
        val circle = fixture("loop_closed_circle").single().copy(tool = Tool.RECTANGLE)
        assertThat(FreehandShapes.loop(circle)).isNull()
    }

    @Test
    fun straight_arrow_with_v_head() {
        val strokes = fixture("arrow_two_strokes_v")
        val arrow = FreehandShapes.arrow(strokes, 0)
        assertThat(arrow).isNotNull()
        arrow!!
        assertThat(arrow.strokeIds).containsExactly("arrow_two_strokes_v-0", "arrow_two_strokes_v-1", "arrow_two_strokes_v-2")
        assertThat(arrow.tail.x).isWithin(6f).of(1000f)
        assertThat(arrow.tip.x).isWithin(6f).of(600f)
        assertThat(arrow.tip.y).isWithin(6f).of(1150f)
    }

    @Test
    fun v_head_in_one_chevron_stroke() {
        val arrow = FreehandShapes.arrow(fixture("arrow_chevron_one_stroke"), 0)
        assertThat(arrow).isNotNull()
        assertThat(arrow!!.strokeIds).hasSize(2)
        assertThat(arrow.tip.x).isWithin(6f).of(640f)
        assertThat(arrow.tail.x).isWithin(6f).of(1020f)
    }

    @Test
    fun head_may_touch_the_shaft_start_drawn_tip_first() {
        val arrow = FreehandShapes.arrow(fixture("arrow_shaft_then_v_up"), 0)
        assertThat(arrow).isNotNull()
        assertThat(arrow!!.tip.x).isWithin(6f).of(620f)
        assertThat(arrow.tail.x).isWithin(6f).of(1000f)
    }

    @Test
    fun two_separate_arm_strokes_on_a_vertical_shaft() {
        val arrow = FreehandShapes.arrow(fixture("arrow_vertical_hook_head_two_strokes"), 0)
        assertThat(arrow).isNotNull()
        assertThat(arrow!!.strokeIds).hasSize(3)
        assertThat(arrow.tip.y).isWithin(6f).of(520f)
    }

    @Test
    fun arrow_with_hook_head_in_one_stroke() {
        for (name in listOf("arrow_hook_right", "arrow_hook_diagonal")) {
            val strokes = fixture(name)
            val arrow = FreehandShapes.arrow(strokes, 0)
            assertThat(arrow).isNotNull()
            arrow!!
            assertThat(arrow.strokeIds).containsExactly(strokes[0].id)
            val end = strokes[0].points.last()
            assertThat(java.lang.Math.hypot((arrow.tip.x - end.x).toDouble(), (arrow.tip.y - end.y).toDouble()))
                .isLessThan(45.0)
            assertThat(arrow.tail.x).isWithin(3f).of(strokes[0].points.first().x)
        }
    }

    @Test
    fun underline_is_not_arrow() {
        val strokes = fixture("neg_underline")
        assertThat(FreehandShapes.arrow(strokes, 0)).isNull()
    }

    @Test
    fun curved_shafts_with_tick_heads_from_the_device_are_arrows() {
        val strokes = fixture("arrows_tick_head_device_v0195")
        // Four bubbles with their note text; each curved shaft is followed by its tick head.
        for (shaft in listOf(5, 19, 25, 32)) {
            val arrow = FreehandShapes.arrow(strokes, shaft)
            assertWithMessage("shaft $shaft").that(arrow).isNotNull()
            assertThat(arrow!!.strokeIds).containsExactly(strokes[shaft].id, strokes[shaft + 1].id)
        }
    }

    @Test
    fun tick_displaced_from_the_shaft_end_is_not_a_head() {
        val strokes = fixture("arrows_tick_head_device_v0195")
        val tick = strokes[6]
        val away = tick.copy(points = tick.points.map { it.copy(x = it.x + 120f, y = it.y + 120f) })
        assertThat(FreehandShapes.arrow(listOf(strokes[5], away), 0)).isNull()
    }

    @Test
    fun straight_continuation_of_the_shaft_is_not_a_tick_head() {
        val shaft = pen("s", 100f, 500f, 400f, 500f)
        val extension = pen("e", 380f, 500f, 400f, 500f, 440f, 500f)
        assertThat(FreehandShapes.arrow(listOf(shaft, extension), 0)).isNull()
    }

    @Test
    fun separate_strokes_that_only_touch_a_shaft_end_are_not_tick_heads() {
        val shaft = pen("s", 100f, 500f, 400f, 500f, 700f, 500f)
        val shapes =
            mapOf(
                "L corner on the shaft end" to pen("t", 400f, 470f, 400f, 500f, 430f, 500f),
                "L corner on the tail end" to pen("t", 700f, 470f, 700f, 500f, 730f, 500f),
                "v across the shaft end" to pen("t", 685f, 470f, 700f, 500f, 715f, 470f),
                "check mark" to pen("t", 690f, 490f, 700f, 500f, 740f, 450f),
            )
        for ((name, tick) in shapes) {
            assertWithMessage(name).that(FreehandShapes.arrow(listOf(shaft, tick), 0)).isNull()
        }
    }

    @Test
    fun negatives_are_not_arrows() {
        for (name in negatives) {
            val strokes = fixture(name)
            for (i in strokes.indices) assertThat(FreehandShapes.arrow(strokes, i)).isNull()
        }
    }

    @Test
    fun loops_are_not_arrows() {
        for (name in loops) {
            assertThat(FreehandShapes.arrow(fixture(name), 0)).isNull()
        }
    }

    @Test
    fun head_far_from_the_shaft_end_is_not_attached() {
        val strokes = fixture("arrow_two_strokes_v").toMutableList()
        val moved =
            strokes[1].copy(points = strokes[1].points.map { it.copy(y = it.y + 60f) })
        val moved2 =
            strokes[2].copy(points = strokes[2].points.map { it.copy(y = it.y + 60f) })
        assertThat(FreehandShapes.arrow(listOf(strokes[0], moved, moved2), 0)).isNull()
    }

    @Test
    fun start_index_out_of_range_or_shape_tool_is_null() {
        val strokes = fixture("arrow_two_strokes_v")
        assertThat(FreehandShapes.arrow(strokes, 9)).isNull()
        assertThat(FreehandShapes.arrow(listOf(strokes[0].copy(tool = Tool.ARROW)) + strokes.drop(1), 0)).isNull()
    }

    @Test
    fun c_u_q_e_eight_check_and_small_ring_are_not_loops() {
        for (name in letterNegatives) {
            for (stroke in fixture(name)) {
                assertWithMessage(name).that(FreehandShapes.loop(stroke)).isNull()
            }
        }
    }

    @Test
    fun c_u_q_e_eight_check_flick_and_small_ring_are_not_arrows() {
        for (name in letterNegatives) {
            val strokes = fixture(name)
            for (i in strokes.indices) assertWithMessage(name).that(FreehandShapes.arrow(strokes, i)).isNull()
        }
    }

    @Test
    fun double_headed_arrow_consumes_both_heads() {
        val strokes = fixture("arrow_double_headed")
        val arrow = FreehandShapes.arrow(strokes, 0)
        assertThat(arrow).isNotNull()
        assertThat(arrow!!.strokeIds).containsExactly("arrow_double_headed-0", "arrow_double_headed-1", "arrow_double_headed-2")
    }

    @Test
    fun loop_one_point_eight_cells_is_the_smallest_accepted() {
        val loop = FreehandShapes.loop(fixture("loop_one_point_eight_cell").single())
        assertThat(loop).isNotNull()
        assertThat(loop!!.bounds.r - loop.bounds.l).isAtLeast(1.7f * NotebookGeometry.CELL)
    }

    @Test
    fun loop_with_lead_in_or_overshoot_has_a_polygon_inside_the_circle() {
        for (name in listOf("loop_lead_in_overshoot", "loop_long_lead_in")) {
            val loop = FreehandShapes.loop(fixture(name).single())
            assertWithMessage(name).that(loop).isNotNull()
            assertThat(loop!!.bounds.l).isAtLeast(800f - 1.5f * NotebookGeometry.CELL - 4f)
        }
    }
}
