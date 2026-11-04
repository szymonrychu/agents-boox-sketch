package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxStrokeInputTest {
    @Test
    fun raw_points_become_a_stroke_with_relative_time_and_tilt() {
        val pts =
            listOf(
                TouchPoint(10f, 20f, 0.5f, 1f, 30, 0, 1_000L),
                TouchPoint(11f, 21f, 0.6f, 1f, 0, 0, 1_016L),
            )
        val stroke = OnyxStrokeInput.strokeFrom(pts, Tool.MARKER, 6f, "s1")!!
        assertThat(stroke.id).isEqualTo("s1")
        assertThat(stroke.tool).isEqualTo(Tool.MARKER)
        assertThat(stroke.points.map { it.tMs }).containsExactly(0L, 16L).inOrder()
        assertThat(stroke.points[0].tiltX).isEqualTo(30f)
        assertThat(stroke.points[1].tiltX).isNull()
        assertThat(stroke.points[1].pressure).isEqualTo(0.6f)
    }

    @Test
    fun empty_point_list_yields_no_stroke() {
        assertThat(OnyxStrokeInput.strokeFrom(emptyList(), Tool.BALLPOINT, 4f, "s")).isNull()
    }

    private class Fixture {
        val view = View(ApplicationProvider.getApplicationContext())
        var callback: RawInputCallback? = null
        val raw =
            object : RawDrawing {
                override fun setLimitRect(
                    limit: Rect,
                    exclude: List<Rect>,
                ) = Unit

                override fun open() = Unit

                override fun setEnabled(enabled: Boolean) = Unit

                override fun setBrush(
                    style: Int,
                    width: Float,
                ) = Unit

                override fun close() = Unit
            }
        val input =
            OnyxStrokeInput(
                ApplicationProvider.getApplicationContext(),
                rawDrawing = { _, cb ->
                    callback = cb
                    raw
                },
                maxPressure = { 4096f },
                afterNextDraw = { _, r -> r.run() },
                fullRefresh = { },
            )

        fun penDown() {
            input.start(view, Rect(0, 0, 100, 100), emptyList())
            callback!!.onBeginRawDrawing(false, TouchPoint(1f, 1f, 0.5f, 1f, 0, 0, 0L))
            assertThat(input.strokeInProgress).isTrue()
        }
    }

    @Test
    fun pen_down_clears_when_raw_drawing_is_paused_mid_stroke() {
        val f = Fixture()
        f.penDown()
        f.input.pause()
        assertThat(f.input.strokeInProgress).isFalse()
    }

    @Test
    fun pen_down_clears_when_raw_drawing_is_held_mid_stroke() {
        val f = Fixture()
        f.penDown()
        f.input.holdUi()
        assertThat(f.input.strokeInProgress).isFalse()
    }

    @Test
    fun pen_down_clears_when_the_session_is_replaced_mid_stroke() {
        val f = Fixture()
        f.penDown()
        f.input.start(View(ApplicationProvider.getApplicationContext()), Rect(0, 0, 100, 100), emptyList())
        assertThat(f.input.strokeInProgress).isFalse()
    }

    @Test
    fun pen_down_clears_when_raw_drawing_is_closed_mid_stroke() {
        val f = Fixture()
        f.penDown()
        f.input.stop()
        assertThat(f.input.strokeInProgress).isFalse()
    }
}
