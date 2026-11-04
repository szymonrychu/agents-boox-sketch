package io.github.szymonrychu.booxsketch.ink

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.OnyxStrokeInput
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import io.github.szymonrychu.booxsketch.onyx.RawDrawing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** The SDK side of raw drawing: records whether it is open and enabled, as the firmware would see it. */
private class RecordingRawDrawing : RawDrawing {
    var open = false
    var on = false

    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun open() {
        open = true
        on = false
    }

    override fun setEnabled(enabled: Boolean) {
        on = enabled
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) = Unit

    override fun close() {
        open = false
        on = false
    }
}

/**
 * The real [OnyxStrokeInput] (only the vendor TouchHelper is faked) under the selector's fallback
 * wrapper, across pause and resume with tool switches in the middle of pen strokes and shape drags:
 * raw drawing always ends in the state the tool asks for, shape drags come through the window
 * callback, and the fallback never fires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxRealInputShapeTest {
    private val diag = mutableListOf<String>()
    private val raw = RecordingRawDrawing()
    private var callback: RawInputCallback? = null
    private val touch = TouchStrokeInput(newId = { "shape" })
    private val board = Rect(0, 0, 100, 100)
    private lateinit var activity: Activity
    private lateinit var view: View
    private lateinit var input: StrokeInput

    @Before
    fun reset() = InputSelector.resetForTest()

    private fun start(scope: TestScope) {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = View(activity)
        val onyx =
            OnyxStrokeInput(
                activity,
                newId = { "pen" },
                rawDrawing = { _, cb ->
                    callback = cb
                    raw
                },
                maxPressure = { 4096f },
                afterNextDraw = { _, r -> r.run() },
                fullRefresh = {},
            )
        input =
            InputSelector(
                probe = { ProbeResult(true, "ok") },
                settings =
                    object : InputSettings {
                        override val forceTouch = false
                    },
                diagnostics = { diag += it },
                scope = scope,
                onyxFactory = { onyx },
                touchFactory = { touch },
                mainExecutor = Executor { it.run() },
            ).select()
        input.start(view, board, emptyList())
    }

    private fun ev(
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                },
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 0.5f
                },
            )
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun window(
        action: Int,
        x: Float,
        y: Float,
    ) = activity.window.callback.dispatchTouchEvent(ev(action, x, y))

    private fun point() = TouchPoint(10f, 10f, 2048f, 1f, 0, 0, 0L)

    /** A pen down on the board as the firmware reports it: the window sees the stylus, raw sends its callback. */
    private fun penDown() {
        window(MotionEvent.ACTION_DOWN, 10f, 10f)
        callback!!.onBeginRawDrawing(false, point())
    }

    @Test
    fun pause_mid_shape_drag_then_pen_while_paused_ends_with_raw_on_after_resume() =
        runTest {
            start(this)
            assertThat(raw.on).isTrue()
            input.setTool(Tool.RECTANGLE, 4f)
            assertThat(raw.on).isFalse()
            assertThat(raw.open).isTrue()
            window(MotionEvent.ACTION_DOWN, 20f, 20f)
            window(MotionEvent.ACTION_MOVE, 60f, 50f)
            assertThat(input.inProgress.value).isNotNull()
            input.pause()
            assertThat(input.inProgress.value).isNull()
            assertThat(withTimeout(1_000) { input.events.first() }).isEqualTo(StrokeEvent.Cancelled)
            input.setTool(Tool.BALLPOINT, 4f)
            assertThat(raw.on).isFalse()
            input.resume()
            assertThat(raw.on).isTrue()
            penDown()
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(diag).isEmpty()
        }

    @Test
    fun shape_tool_picked_mid_pen_stroke_survives_pause_and_resume_and_draws_from_the_window() =
        runTest {
            start(this)
            penDown()
            input.setTool(Tool.ARROW, 4f)
            assertThat(raw.on).isFalse()
            assertThat(input.strokeInProgress).isFalse()
            input.pause()
            input.resume()
            assertThat(raw.on).isFalse()
            window(MotionEvent.ACTION_DOWN, 20f, 20f)
            window(MotionEvent.ACTION_MOVE, 80f, 70f)
            window(MotionEvent.ACTION_UP, 80f, 70f)
            val committed = withTimeout(1_000) { input.events.first() } as StrokeEvent.Committed
            assertThat(committed.stroke.tool).isEqualTo(Tool.ARROW)
            input.setTool(Tool.BALLPOINT, 4f)
            assertThat(raw.on).isTrue()
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(diag).isEmpty()
        }

    @Test
    fun pen_picked_mid_shape_drag_cancels_the_drag_and_turns_raw_back_on() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            window(MotionEvent.ACTION_DOWN, 20f, 20f)
            window(MotionEvent.ACTION_MOVE, 60f, 50f)
            input.setTool(Tool.BALLPOINT, 4f)
            assertThat(input.inProgress.value).isNull()
            assertThat(withTimeout(1_000) { input.events.first() }).isEqualTo(StrokeEvent.Cancelled)
            assertThat(raw.on).isTrue()
            // The rest of the old drag no longer draws a shape.
            window(MotionEvent.ACTION_MOVE, 70f, 60f)
            assertThat(input.inProgress.value).isNull()
            input.pause()
            assertThat(raw.on).isFalse()
            input.setTool(Tool.RECTANGLE, 4f)
            input.setTool(Tool.BALLPOINT, 4f)
            input.resume()
            assertThat(raw.on).isTrue()
            penDown()
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(diag).isEmpty()
        }
}
