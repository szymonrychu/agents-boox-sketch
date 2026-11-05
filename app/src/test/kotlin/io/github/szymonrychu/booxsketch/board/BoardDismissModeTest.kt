package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import io.github.szymonrychu.booxsketch.ui.AppShell
import io.github.szymonrychu.booxsketch.ui.BOARD_TAB_ID
import io.github.szymonrychu.booxsketch.ui.ShellState
import io.github.szymonrychu.booxsketch.ui.TabRegistry
import io.github.szymonrychu.booxsketch.ui.TabSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Dismiss mode: with the strip, a popup or the panel open, a touch on the board dismisses them and draws nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardDismissModeTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val state = ShellState()
    private var host: View? = null
    private lateinit var vm: BoardViewModel

    private fun show(input: io.github.szymonrychu.booxsketch.ink.StrokeInput) {
        val registry = TabRegistry()
        registry.register(TabSpec(BOARD_TAB_ID, "Drawings", 10) { })
        registry.register(TabSpec("spec", "Spec", 20) { })
        vm = BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        compose.setContent {
            host = LocalView.current
            AppShell(
                tabs = registry,
                board = { chrome -> BoardScreen(vm, input, chrome = chrome) },
                state = state,
                insets = WindowInsets(0, 0, 0, 0),
            )
        }
        val v = surface()
        compose.runOnUiThread { v.surfaceCreated(v.holder) }
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

    private fun rectOf(tag: String): Rect {
        val d = compose.density.density
        val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return Rect((b.left.value * d).toInt(), (b.top.value * d).toInt(), (b.right.value * d).toInt(), (b.bottom.value * d).toInt())
    }

    private fun boardRect() = surface().let { Rect(0, 0, it.width, it.height) }

    private fun ev(
        action: Int,
        type: Int,
        time: Long,
        x: Float = X,
        y: Float = Y,
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

    private fun down(type: Int) {
        compose.runOnUiThread { surface().dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, type, DOWN_AT)) }
        compose.waitForIdle()
    }

    private fun drag(type: Int) {
        compose.runOnUiThread { surface().dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, type, DOWN_AT + 10, X - 30f, Y - 30f)) }
        compose.waitForIdle()
    }

    private fun up(type: Int) {
        compose.runOnUiThread { surface().dispatchTouchEvent(ev(MotionEvent.ACTION_UP, type, DOWN_AT + 50, X - 30f, Y - 30f)) }
        compose.waitForIdle()
    }

    private fun lastStart(input: FreezingInput) = input.log.last { it.startsWith("start:") }

    @Test
    fun open_strip_excludes_the_whole_board_from_raw_drawing() {
        val input = FreezingInput()
        show(input)

        assertThat(state.chromeVisible).isTrue()
        assertThat(lastStart(input)).contains(boardRect().toString())
    }

    @Test
    fun hidden_strip_leaves_the_board_free() {
        val input = FreezingInput()
        show(input)
        down(MotionEvent.TOOL_TYPE_STYLUS)
        up(MotionEvent.TOOL_TYPE_STYLUS)

        assertThat(state.chromeVisible).isFalse()
        assertThat(lastStart(input)).isEqualTo("start:${listOf(rectOf(TAG_INDICATOR))}")
    }

    @Test
    fun pen_down_dismisses_without_waiting_for_the_lift() {
        val input = FreezingInput()
        show(input)

        down(MotionEvent.TOOL_TYPE_STYLUS)

        assertThat(state.chromeVisible).isFalse()
    }

    @Test
    fun excludes_keep_the_board_until_the_lift_then_drop_to_the_indicator_only() {
        val input = FreezingInput()
        show(input)
        input.log.clear()

        down(MotionEvent.TOOL_TYPE_STYLUS)
        drag(MotionEvent.TOOL_TYPE_STYLUS)
        assertThat(input.log.filter { it.startsWith("start:") }.all { it.contains(boardRect().toString()) }).isTrue()
        // One hold for the overlays, plus at most the refresher's own for the full refresh.
        assertThat(input.log.count { it == "hold" }).isAtMost(2)

        up(MotionEvent.TOOL_TYPE_STYLUS)
        val want = "start:${listOf(rectOf(TAG_INDICATOR))}"
        assertThat(lastStart(input)).isEqualTo(want)
        // Raw drawing is released with the board still excluded; the lift only narrows it to the indicator.
        assertThat(input.log.filter { it.startsWith("start:") }).doesNotContain("start:[]")
        assertThat(input.log.toString()).contains("release")
        assertThat(input.log.count { it == "hold" }).isEqualTo(input.log.count { it == "release" })
    }

    @Test
    fun pen_gesture_makes_no_stroke_in_the_model_even_with_a_touch_input() {
        val input = TouchStrokeInput()
        show(input)
        val before =
            vm.notebook.value.pages[vm.notebook.value.current]
                .strokes

        down(MotionEvent.TOOL_TYPE_STYLUS)
        drag(MotionEvent.TOOL_TYPE_STYLUS)
        up(MotionEvent.TOOL_TYPE_STYLUS)

        assertThat(state.chromeVisible).isFalse()
        assertThat(
            vm.notebook.value.pages[vm.notebook.value.current]
                .strokes,
        ).isEqualTo(before)
        assertThat(input.inProgress.value).isNull()
    }

    @Test
    fun shape_tool_drag_is_swallowed_too() {
        val input = TouchStrokeInput()
        input.setTool(Tool.RECTANGLE, 4f)
        show(input)

        down(MotionEvent.TOOL_TYPE_STYLUS)
        drag(MotionEvent.TOOL_TYPE_STYLUS)
        assertThat(input.inProgress.value).isNull()
        up(MotionEvent.TOOL_TYPE_STYLUS)

        assertThat(
            vm.notebook.value.pages[vm.notebook.value.current]
                .strokes,
        ).isEmpty()
    }

    @Test
    fun finger_drag_dismisses_and_draws_nothing() {
        val input = FreezingInput()
        show(input)

        down(MotionEvent.TOOL_TYPE_FINGER)
        drag(MotionEvent.TOOL_TYPE_FINGER)
        up(MotionEvent.TOOL_TYPE_FINGER)

        assertThat(state.chromeVisible).isFalse()
    }

    @Test
    fun finger_down_seen_only_by_the_window_callback_dismisses() {
        val input = FreezingInput()
        show(input)

        compose.runOnUiThread { surface().onWindowTouch(ev(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, DOWN_AT)) }
        compose.waitForIdle()
        assertThat(state.chromeVisible).isFalse()

        compose.runOnUiThread { surface().onWindowTouch(ev(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, DOWN_AT + 50)) }
        compose.waitForIdle()
        assertThat(lastStart(input)).isEqualTo("start:${listOf(rectOf(TAG_INDICATOR))}")
    }

    @Test
    fun window_down_on_the_strip_does_not_dismiss() {
        val input = FreezingInput()
        show(input)
        val dock = rectOf(TAG_DOCK)

        compose.runOnUiThread {
            surface().onWindowTouch(
                ev(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, DOWN_AT, dock.centerX().toFloat(), dock.centerY().toFloat()),
            )
        }
        compose.waitForIdle()

        assertThat(state.chromeVisible).isTrue()
    }

    private companion object {
        const val DOWN_AT = 1_000L
        const val X = 200f
        const val Y = 300f
    }
}
