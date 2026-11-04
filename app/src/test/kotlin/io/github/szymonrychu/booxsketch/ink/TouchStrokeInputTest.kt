package io.github.szymonrychu.booxsketch.ink

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TouchStrokeInputTest {
    private val view = View(ApplicationProvider.getApplicationContext())

    private fun input(finger: Boolean = false): TouchStrokeInput =
        TouchStrokeInput(fingerEnabled = { finger }, newId = { "id" }).also {
            it.start(view, Rect(0, 0, 1000, 1000), listOf(Rect(900, 900, 1000, 1000)))
            it.setTool(Tool.MARKER, 6f)
        }

    private fun coords(
        x: Float,
        y: Float,
        p: Float = 0.5f,
        tilt: Float = 0f,
    ) = MotionEvent.PointerCoords().also {
        it.x = x
        it.y = y
        it.pressure = p
        it.setAxisValue(MotionEvent.AXIS_TILT, tilt)
    }

    private fun event(
        action: Int,
        type: Int,
        time: Long,
        c: MotionEvent.PointerCoords,
    ): MotionEvent {
        val props =
            MotionEvent.PointerProperties().also {
                it.id = 0
                it.toolType = type
            }
        return MotionEvent.obtain(100L, time, action, 1, arrayOf(props), arrayOf(c), 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun swipe(type: Int) {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, type, 100L, coords(10f, 10f)))
        // addBatch appends a newer current sample and pushes the previous one into history,
        // so build the event from the oldest sample and batch forward in time.
        val move = event(MotionEvent.ACTION_MOVE, type, 110L, coords(20f, 20f, 0.6f, 0.1f))
        move.addBatch(120L, arrayOf(coords(30f, 30f, 0.7f, 0.2f)), 0)
        move.addBatch(130L, arrayOf(coords(40f, 40f, 0.9f, 0.3f)), 0)
        view.dispatchTouchEvent(move)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, type, 140L, coords(50f, 50f)))
    }

    private fun next(i: TouchStrokeInput): StrokeEvent? = runBlocking { withTimeoutOrNull(200) { i.events.first() } }

    @Test
    fun finger_ignored_by_default() {
        val i = input()
        swipe(MotionEvent.TOOL_TYPE_FINGER)
        assertThat(next(i)).isNull()
    }

    @Test
    fun finger_draws_when_enabled() {
        val i = input(finger = true)
        swipe(MotionEvent.TOOL_TYPE_FINGER)
        assertThat(next(i)).isInstanceOf(StrokeEvent.Committed::class.java)
    }

    @Test
    fun stylus_move_with_history_builds_stroke() {
        val i = input()
        swipe(MotionEvent.TOOL_TYPE_STYLUS)
        val s = (next(i) as StrokeEvent.Committed).stroke
        assertThat(s.id).isEqualTo("id")
        assertThat(s.tool).isEqualTo(Tool.MARKER)
        assertThat(s.width).isEqualTo(6f)
        assertThat(s.points.map { it.x }).containsExactly(10f, 20f, 30f, 40f, 50f).inOrder()
        assertThat(s.points.map { it.tMs }).containsExactly(0L, 10L, 20L, 30L, 40L).inOrder()
        assertThat(s.points[1].pressure).isWithin(0.001f).of(0.6f)
        assertThat(s.points[3].tiltX).isWithin(0.001f).of(0.3f)
    }

    @Test
    fun excluded_area_and_pause_are_ignored() {
        val i = input()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100L, coords(950f, 950f)))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 110L, coords(950f, 950f)))
        i.pause()
        swipe(MotionEvent.TOOL_TYPE_STYLUS)
        assertThat(next(i)).isNull()
        i.resume()
        swipe(MotionEvent.TOOL_TYPE_STYLUS)
        assertThat(next(i)).isInstanceOf(StrokeEvent.Committed::class.java)
    }

    @Test
    fun eraser_end_and_eraser_mode_emit_erase_events() {
        val i = input()
        swipe(MotionEvent.TOOL_TYPE_ERASER)
        assertThat(next(i)).isEqualTo(StrokeEvent.Erase(10f, 10f, 8f))
        val j = input()
        j.eraserMode = true
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100L, coords(5f, 6f)))
        assertThat(next(j)).isEqualTo(StrokeEvent.Erase(5f, 6f, 8f))
    }

    @Test
    fun stroke_in_progress_is_visible_before_lift() {
        val i = input()
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        assertThat(i.inProgress.value).isNull()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, stylus, 100L, coords(10f, 10f)))
        assertThat(
            i.inProgress.value
                ?.points
                ?.map { it.x },
        ).containsExactly(10f)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, stylus, 110L, coords(20f, 20f)))
        val live = i.inProgress.value!!
        assertThat(live.points.map { it.x }).containsExactly(10f, 20f).inOrder()
        assertThat(live.tool).isEqualTo(Tool.MARKER)
        assertThat(live.width).isEqualTo(6f)
        assertThat(next(i)).isNull()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, stylus, 120L, coords(30f, 30f)))
        assertThat(i.inProgress.value).isNull()
        assertThat(next(i)).isInstanceOf(StrokeEvent.Committed::class.java)
    }

    @Test
    fun cancel_pause_and_eraser_show_no_stroke_in_progress() {
        val i = input()
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, stylus, 100L, coords(10f, 10f)))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, stylus, 110L, coords(10f, 10f)))
        assertThat(i.inProgress.value).isNull()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, stylus, 120L, coords(10f, 10f)))
        i.pause()
        assertThat(i.inProgress.value).isNull()
        i.resume()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_ERASER, 130L, coords(10f, 10f)))
        assertThat(i.inProgress.value).isNull()
    }

    @Test
    fun stroke_in_progress_from_pen_down_to_pen_up() {
        val i = input()
        assertThat(i.strokeInProgress).isFalse()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100L, coords(10f, 10f)))
        assertThat(i.strokeInProgress).isTrue()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 140L, coords(50f, 50f)))
        assertThat(i.strokeInProgress).isFalse()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 200L, coords(10f, 10f)))
        i.stop()
        assertThat(i.strokeInProgress).isFalse()
    }

    @Test
    fun live_and_committed_strokes_carry_the_selected_colour() {
        val i = input()
        i.setColor(PenColor.ORANGE)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100L, coords(10f, 10f)))
        assertThat(i.inProgress.value!!.color).isEqualTo(PenColor.ORANGE)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 110L, coords(20f, 20f)))
        assertThat((next(i) as StrokeEvent.Committed).stroke.color).isEqualTo(PenColor.ORANGE)
    }

    @Test
    fun shape_drag_is_published_live_with_the_shape_tool_and_committed_on_lift() {
        val i = input()
        i.setTool(Tool.RECTANGLE, 4f)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100L, coords(10f, 10f)))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_STYLUS, 110L, coords(60f, 40f)))
        val live = i.inProgress.value!!
        assertThat(live.tool).isEqualTo(Tool.RECTANGLE)
        assertThat(live.points.last().x).isEqualTo(60f)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 120L, coords(70f, 50f)))
        assertThat((next(i) as StrokeEvent.Committed).stroke.tool).isEqualTo(Tool.RECTANGLE)
        assertThat(i.inProgress.value).isNull()
    }

    @Test
    fun the_commit_is_sent_before_the_live_stroke_is_cleared() {
        val i = input()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var commits = 0
        var commitsWhenCleared = -1
        scope.launch { i.events.collect { commits++ } }
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100L, coords(10f, 10f)))
        scope.launch { i.inProgress.collect { if (it == null) commitsWhenCleared = commits } }
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 110L, coords(20f, 20f)))
        scope.cancel()
        assertThat(commitsWhenCleared).isEqualTo(1)
    }

    @Test
    fun a_drop_without_a_commit_sends_cancelled_and_a_lift_does_not() {
        val i = input()
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, stylus, 100L, coords(10f, 10f)))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, stylus, 110L, coords(10f, 10f)))
        assertThat(next(i)).isEqualTo(StrokeEvent.Cancelled)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, stylus, 120L, coords(10f, 10f)))
        i.pause()
        assertThat(next(i)).isEqualTo(StrokeEvent.Cancelled)
        i.resume()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, stylus, 130L, coords(10f, 10f)))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, stylus, 140L, coords(20f, 20f)))
        assertThat(next(i)).isInstanceOf(StrokeEvent.Committed::class.java)
        assertThat(next(i)).isNull()
    }

    @Test
    fun window_and_view_copies_of_one_drag_are_handled_once_and_counted_per_source() {
        val i = input()
        i.setTool(Tool.RECTANGLE, 4f)
        val seen = mutableListOf<DragSource>()
        i.onShapeEvent = { seen += it }
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        val drag =
            listOf(
                event(MotionEvent.ACTION_DOWN, stylus, 100L, coords(10f, 10f)),
                event(MotionEvent.ACTION_MOVE, stylus, 110L, coords(60f, 40f)),
                event(MotionEvent.ACTION_UP, stylus, 120L, coords(70f, 50f)),
            )
        drag.forEach {
            assertThat(i.feed(it, DragSource.WINDOW)).isTrue()
            view.dispatchTouchEvent(it)
        }
        val stroke = (next(i) as StrokeEvent.Committed).stroke
        assertThat(stroke.points.map { it.x }).containsExactly(10f, 60f, 70f).inOrder()
        assertThat(next(i)).isNull()
        assertThat(seen.count { it == DragSource.WINDOW }).isEqualTo(3)
        assertThat(seen.count { it == DragSource.VIEW }).isEqualTo(3)
    }

    @Test
    fun window_feed_ignores_the_rest_of_a_gesture_that_started_off_the_board() {
        val i = input()
        i.setTool(Tool.ARROW, 4f)
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        assertThat(i.feed(event(MotionEvent.ACTION_DOWN, stylus, 100L, coords(950f, 950f)), DragSource.WINDOW)).isFalse()
        assertThat(i.feed(event(MotionEvent.ACTION_MOVE, stylus, 110L, coords(50f, 50f)), DragSource.WINDOW)).isFalse()
        assertThat(i.feed(event(MotionEvent.ACTION_UP, stylus, 120L, coords(50f, 50f)), DragSource.WINDOW)).isFalse()
        assertThat(i.inProgress.value).isNull()
        assertThat(next(i)).isNull()
    }

    @Test
    fun window_feed_before_start_takes_nothing() {
        val i = TouchStrokeInput(newId = { "id" })
        val down = event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100L, coords(10f, 10f))
        assertThat(i.feed(down, DragSource.WINDOW)).isFalse()
    }
}
