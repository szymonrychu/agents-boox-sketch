package io.github.szymonrychu.booxsketch.ink

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** A silent Onyx input whose raw drawing is off while a shape tool is selected, as in OnyxStrokeInput. */
private class ShapeAwareRawInput(
    private val log: MutableList<String>,
) : StrokeInput,
    RawActivitySource {
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null
    override val events: Flow<StrokeEvent> = emptyFlow()
    var tool = Tool.BALLPOINT
    var holds = 0

    /** When false, [rawActive] ignores the tool (to test that the wrapper itself disarms the watch). */
    var shapeTurnsRawOff = true
    override val rawActive: Boolean get() = holds == 0 && !(shapeTurnsRawOff && tool.isShape)

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        log += "onyx:start"
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        this.tool = tool
        log += "onyx:tool:${tool.wire}"
    }

    override fun setColor(color: PenColor) {
        log += "onyx:color:${color.wire}"
    }

    override fun holdUi() {
        holds++
    }

    override fun releaseUi() {
        holds--
    }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() {
        log += "onyx:stop"
    }
}

/**
 * Shape tools on the Onyx path: raw drawing stays off and the drag comes from plain touch events
 * on the board view, so raw ink never draws freehand on top of a rectangle or arrow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxShapeRoutingTest {
    private val log = mutableListOf<String>()
    private val onyx = ShapeAwareRawInput(log)
    private val touch = TouchStrokeInput(newId = { "shape" })
    private val board = Rect(0, 0, 100, 100)
    private val tabBar = Rect(0, 0, 100, 10)
    private lateinit var input: StrokeInput
    private lateinit var view: View
    private lateinit var activity: Activity
    private lateinit var selector: InputSelector

    @Before
    fun reset() = InputSelector.resetForTest()

    private fun start(scope: TestScope) {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = View(activity)
        selector =
            InputSelector(
                probe = { ProbeResult(true, "ok") },
                settings =
                    object : InputSettings {
                        override val forceTouch = false
                    },
                diagnostics = { log += "diag" },
                scope = scope,
                onyxFactory = { onyx },
                touchFactory = { touch },
                mainExecutor = Executor { it.run() },
            )
        input = selector.select()
        input.start(view, board, listOf(tabBar))
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

    private fun drag(
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
    ) {
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, from.first, from.second))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, to.first, to.second))
    }

    @Test
    fun shape_drag_on_the_board_is_published_live_and_committed_on_lift() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            drag(20f to 20f, 60f to 50f)
            assertThat(input.inProgress.value!!.tool).isEqualTo(Tool.RECTANGLE)
            assertThat(input.strokeInProgress).isTrue()
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 60f, 50f))
            assertThat(input.strokeInProgress).isFalse()
            val committed = input.events.first() as StrokeEvent.Committed
            assertThat(committed.stroke.tool).isEqualTo(Tool.RECTANGLE)
            assertThat(input.inProgress.value).isNull()
        }

    @Test
    fun shape_tool_reaches_the_raw_input_so_it_turns_raw_drawing_off() =
        runTest {
            start(this)
            input.setTool(Tool.ARROW, 4f)
            assertThat(log).contains("onyx:tool:arrow")
            assertThat((onyx as RawActivitySource).rawActive).isFalse()
        }

    @Test
    fun shape_drags_respect_the_exclude_rects_of_later_starts() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            input.start(view, board, listOf(Rect(0, 0, 100, 50)))
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f))
            assertThat(input.inProgress.value).isNull()
            assertThat(input.strokeInProgress).isFalse()
        }

    @Test
    fun back_on_a_pen_tool_the_touch_path_stops_drawing() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            input.setTool(Tool.BALLPOINT, 4f)
            drag(20f to 20f, 60f to 50f)
            assertThat(input.inProgress.value).isNull()
            assertThat(touch.strokeInProgress).isFalse()
        }

    @Test
    fun back_on_a_pen_tool_a_silent_raw_path_still_falls_back() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            input.setTool(Tool.BALLPOINT, 4f)
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 50f, 50f))
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).contains("diag")
        }

    @Test
    fun stylus_down_with_a_shape_tool_never_arms_the_fallback_watch() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            activity.window.callback.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 50f, 50f))
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 50f, 50f))
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).doesNotContain("diag")
        }

    @Test
    fun selecting_a_shape_tool_cancels_an_armed_watch() =
        runTest {
            start(this)
            onyx.shapeTurnsRawOff = false
            activity.window.callback.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 50f, 50f))
            advanceTimeBy(100)
            input.setTool(Tool.ARROW, 4f)
            input.setTool(Tool.BALLPOINT, 4f)
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).doesNotContain("diag")
        }

    @Test
    fun holds_during_a_shape_tool_reach_the_raw_input_and_are_released() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            input.holdUi()
            assertThat(onyx.holds).isEqualTo(1)
            input.setTool(Tool.BALLPOINT, 4f)
            input.releaseUi()
            assertThat(onyx.holds).isEqualTo(0)
            assertThat((onyx as RawActivitySource).rawActive).isTrue()
        }

    @Test
    fun stop_with_a_shape_tool_stops_the_touch_path() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            input.stop()
            drag(20f to 20f, 60f to 50f)
            assertThat(input.inProgress.value).isNull()
        }

    @Test
    fun colour_reaches_both_inputs() =
        runTest {
            start(this)
            input.setColor(PenColor.PURPLE)
            assertThat(log).contains("onyx:color:purple")
            input.setTool(Tool.ARROW, 4f)
            drag(20f to 20f, 60f to 50f)
            assertThat(input.inProgress.value!!.color).isEqualTo(PenColor.PURPLE)
        }

    @Test
    fun shape_drag_seen_only_by_the_window_callback_still_draws_and_commits() =
        runTest {
            start(this)
            input.setTool(Tool.RECTANGLE, 4f)
            // The view is not in the activity's hierarchy: only the window wrapper sees these, as when raw claims the drag.
            activity.window.callback.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f))
            activity.window.callback.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 60f, 50f))
            assertThat(input.inProgress.value!!.tool).isEqualTo(Tool.RECTANGLE)
            activity.window.callback.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 60f, 50f))
            val committed = input.events.first() as StrokeEvent.Committed
            assertThat(committed.stroke.tool).isEqualTo(Tool.RECTANGLE)
            assertThat(input.inProgress.value).isNull()
            assertThat(selector.shapeDrags.value).isEqualTo(ShapeDragCounts(window = 3, view = 0))
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).doesNotContain("diag")
        }

    @Test
    fun one_drag_seen_by_window_and_view_commits_once_and_counts_both_sources() =
        runTest {
            start(this)
            input.setTool(Tool.ARROW, 4f)
            listOf(
                ev(MotionEvent.ACTION_DOWN, 20f, 20f),
                ev(MotionEvent.ACTION_MOVE, 60f, 50f),
                ev(MotionEvent.ACTION_UP, 60f, 50f),
            ).forEach {
                activity.window.callback.dispatchTouchEvent(it)
                view.dispatchTouchEvent(it)
            }
            val committed = input.events.first() as StrokeEvent.Committed
            assertThat(committed.stroke.points).hasSize(3)
            assertThat(selector.shapeDrags.value).isEqualTo(ShapeDragCounts(window = 3, view = 3))
        }

    @Test
    fun window_callback_feeds_no_drag_with_a_pen_tool() =
        runTest {
            start(this)
            activity.window.callback.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f))
            activity.window.callback.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 60f, 50f))
            assertThat(input.inProgress.value).isNull()
            assertThat(selector.shapeDrags.value).isEqualTo(ShapeDragCounts())
        }
}
