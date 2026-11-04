package io.github.szymonrychu.booxsketch.onyx

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import io.github.szymonrychu.booxsketch.ink.CommittedInkSurface
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

    /** A host view that records when the input asks it to bring committed ink up to date. */
    private class SyncView(
        context: Context,
        private val order: MutableList<String>,
    ) : View(context),
        CommittedInkSurface {
        override fun syncCommitted() {
            order += "sync"
        }
    }

    private class Fixture {
        val order = mutableListOf<String>()
        val view = SyncView(ApplicationProvider.getApplicationContext(), order)
        var callback: RawInputCallback? = null
        val enables = mutableListOf<Boolean>()
        val idleTimeouts = mutableListOf<Int>()
        val raw =
            object : RawDrawing {
                override fun setLimitRect(
                    limit: Rect,
                    exclude: List<Rect>,
                ) = Unit

                override fun open() = Unit

                override fun setEnabled(enabled: Boolean) {
                    enables += enabled
                    order += "raw:$enabled"
                }

                override fun setPenUpRefreshTimeMs(ms: Int) {
                    idleTimeouts += ms
                }

                override fun setBrush(
                    style: Int,
                    width: Float,
                    color: Int,
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

    @Test
    fun draw_committed_while_raw_is_active_redraws_without_toggling_raw_drawing() {
        val f = Fixture()
        f.input.start(f.view, Rect(0, 0, 100, 100), emptyList())
        f.enables.clear()
        var drawn = 0
        f.input.drawCommitted { drawn++ }
        assertThat(drawn).isEqualTo(1)
        assertThat(f.enables).isEmpty()
    }

    @Test
    fun draw_committed_only_redraws_when_raw_is_paused_held_or_on_a_shape_tool() {
        val f = Fixture()
        f.input.start(f.view, Rect(0, 0, 100, 100), emptyList())
        f.input.pause()
        f.enables.clear()
        var drawn = 0
        f.input.drawCommitted { drawn++ }
        f.input.resume()
        f.input.holdUi()
        f.input.drawCommitted { drawn++ }
        f.input.releaseUi()
        f.input.setTool(Tool.RECTANGLE, 4f)
        f.enables.clear()
        f.input.drawCommitted { drawn++ }
        assertThat(drawn).isEqualTo(3)
        assertThat(f.enables).isEmpty()
    }

    @Test
    fun refresh_still_brackets_the_redraw_with_a_disable_and_enable() {
        val f = Fixture()
        f.input.start(f.view, Rect(0, 0, 100, 100), emptyList())
        f.enables.clear()
        f.input.refresh { f.enables += true }
        assertThat(f.enables).containsExactly(false, true, true).inOrder()
    }

    @Test
    fun every_open_sets_the_pen_idle_timeout() {
        val f = Fixture()
        f.input.start(f.view, Rect(0, 0, 100, 100), emptyList())
        f.input.start(f.view, Rect(0, 0, 50, 50), emptyList())
        assertThat(f.idleTimeouts).containsExactly(OnyxStrokeInput.PEN_IDLE_MS, OnyxStrokeInput.PEN_IDLE_MS)
    }

    @Test
    fun pen_idle_syncs_the_host_surface_unless_the_pen_is_down_again() {
        val f = Fixture()
        f.penDown()
        f.callback!!.onPenUpRefresh(RectF(0f, 0f, 1f, 1f))
        assertThat(f.order).doesNotContain("sync")
        f.callback!!.onEndRawDrawing(false, TouchPoint(1f, 1f, 0.5f, 1f, 0, 0, 0L))
        f.order.clear()
        f.callback!!.onPenUpRefresh(RectF(0f, 0f, 1f, 1f))
        assertThat(f.order).containsExactly("sync")
    }

    @Test
    fun the_host_surface_is_synced_before_raw_drawing_turns_off_or_closes() {
        val f = Fixture()
        f.input.start(f.view, Rect(0, 0, 100, 100), emptyList())
        val offs =
            listOf<Pair<() -> Unit, () -> Unit>>(
                { f.input.holdUi() } to { f.input.releaseUi() },
                { f.input.pause() } to { f.input.resume() },
                { f.input.refresh {} } to {},
                { f.input.setTool(Tool.ARROW, 4f) } to { f.input.setTool(Tool.BALLPOINT, 4f) },
                { f.input.start(f.view, Rect(0, 0, 60, 60), emptyList()) } to {},
            )
        for ((off, back) in offs) {
            f.order.clear()
            off()
            assertThat(f.order.first()).isEqualTo("sync")
            back()
        }
        f.order.clear()
        f.input.stop()
        assertThat(f.order).containsExactly("sync")
    }

    @Test
    fun raw_live_follows_pause_hold_and_shape_tool() {
        val f = Fixture()
        assertThat(f.input.rawLive).isFalse()
        f.input.start(f.view, Rect(0, 0, 100, 100), emptyList())
        assertThat(f.input.rawLive).isTrue()
        f.input.pause()
        assertThat(f.input.rawLive).isFalse()
        f.input.resume()
        f.input.holdUi()
        assertThat(f.input.rawLive).isFalse()
        f.input.releaseUi()
        f.input.setTool(Tool.RECTANGLE, 4f)
        assertThat(f.input.rawLive).isFalse()
        f.input.setTool(Tool.BALLPOINT, 4f)
        assertThat(f.input.rawLive).isTrue()
        f.input.stop()
        assertThat(f.input.rawLive).isFalse()
    }

    @Test
    fun raw_is_not_live_while_a_new_session_waits_for_its_first_draw() {
        var pending: Runnable? = null
        val input =
            OnyxStrokeInput(
                ApplicationProvider.getApplicationContext(),
                rawDrawing = { _, _ -> Fixture().raw },
                maxPressure = { 4096f },
                afterNextDraw = { _, r -> pending = r },
                fullRefresh = { },
            )
        input.start(View(ApplicationProvider.getApplicationContext()), Rect(0, 0, 100, 100), emptyList())
        assertThat(input.rawLive).isFalse()
        pending!!.run()
        assertThat(input.rawLive).isTrue()
    }
}
