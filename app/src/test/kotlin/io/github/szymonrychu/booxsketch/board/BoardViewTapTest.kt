package io.github.szymonrychu.booxsketch.board

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardViewTapTest {
    @get:Rule val compose = createComposeRule()

    private var taps = 0
    private var host: View? = null

    private fun show(input: StrokeInput) {
        compose.setContent {
            host = LocalView.current
            BoardView(
                notebook = Notebook(Canvas(100, 200, 300f), listOf(Page(emptyList(), false)), 0),
                input = input,
                onStroke = {},
                onErase = { _, _, _ -> },
                modifier = Modifier.fillMaxSize(),
                onBoardTap = { taps++ },
            )
        }
        compose.waitForIdle()
    }

    private fun surface(): BoardSurfaceView {
        fun find(v: View): BoardSurfaceView? =
            if (v is BoardSurfaceView) {
                v
            } else {
                (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            }
        return find(host!!.rootView)!!
    }

    private fun event(
        action: Int,
        type: Int,
        time: Long,
        x: Float,
        y: Float,
    ): MotionEvent {
        val props =
            MotionEvent.PointerProperties().also {
                it.id = 0
                it.toolType = type
            }
        val coords =
            MotionEvent.PointerCoords().also {
                it.x = x
                it.y = y
                it.pressure = 0.5f
            }
        return MotionEvent.obtain(DOWN_AT, time, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun gesture(
        type: Int,
        upAfterMs: Long = 50L,
        moveBy: Float = 0f,
    ) {
        val v = surface()
        compose.runOnUiThread {
            v.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, type, DOWN_AT, 100f, 100f))
            if (moveBy != 0f) v.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, type, DOWN_AT + 10, 100f + moveBy, 100f))
            v.dispatchTouchEvent(event(MotionEvent.ACTION_UP, type, DOWN_AT + upAfterMs, 100f + moveBy, 100f))
        }
        compose.waitForIdle()
    }

    @Test
    fun finger_tap_calls_onBoardTap() {
        show(FreezingInput())
        gesture(MotionEvent.TOOL_TYPE_FINGER)
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun finger_drag_does_not() {
        show(FreezingInput())
        val slop = ViewConfiguration.get(surface().context).scaledTouchSlop
        gesture(MotionEvent.TOOL_TYPE_FINGER, moveBy = slop * 2f + 1f)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun finger_long_press_does_not() {
        show(FreezingInput())
        gesture(MotionEvent.TOOL_TYPE_FINGER, upAfterMs = 301L)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun stylus_tap_does_not() {
        show(FreezingInput())
        gesture(MotionEvent.TOOL_TYPE_STYLUS)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun tap_during_stroke_does_not() {
        val input = FreezingInput()
        show(input)
        input.strokeInProgress = true
        gesture(MotionEvent.TOOL_TYPE_FINGER)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun tap_works_with_a_shape_tool_selected() {
        // The shape drag input replaces the view's touch listener; the tap must still reach the board.
        val input = TouchStrokeInput()
        input.setTool(Tool.RECTANGLE, 4f)
        show(input)
        val v = surface()
        compose.runOnUiThread { v.surfaceCreated(v.holder) }
        compose.waitForIdle()
        gesture(MotionEvent.TOOL_TYPE_FINGER)
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun tap_works_when_the_view_listener_consumes_every_event() {
        show(FreezingInput())
        val v = surface()
        compose.runOnUiThread { v.setOnTouchListener { _, _ -> true } }
        gesture(MotionEvent.TOOL_TYPE_FINGER)
        assertThat(taps).isEqualTo(1)
    }

    private companion object {
        const val DOWN_AT = 1_000L
    }
}
