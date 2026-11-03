package io.github.szymonrychu.booxsketch.ink

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

/** An Onyx input that never delivers a raw callback; [rawActive] says whether raw drawing is on. */
private class SilentRawInput(
    private val log: MutableList<String>,
    private val name: String,
) : StrokeInput,
    RawActivitySource {
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null
    override val events: Flow<StrokeEvent> = emptyFlow()
    override var rawActive = true

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        log += "$name:start"
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() {
        log += "$name:stop"
    }
}

/**
 * Regression for v0.3.2 (live ink lost, strokes appeared only on pen lift): the 300 ms watch
 * switched the process to touch input on any stylus down in the window, including pen taps on the
 * toolbar and the tab bar, where raw drawing never calls back. Touch commits strokes on ACTION_UP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxFallbackWatchTest {
    private val log = mutableListOf<String>()
    private val onyx = SilentRawInput(log, "onyx")

    /** Board 100x100 at the window origin; the tab bar overlay covers its top 10 px. */
    private val board = Rect(0, 0, 100, 100)
    private val tabBar = Rect(0, 0, 100, 10)

    @Before
    fun reset() = InputSelector.resetForTest()

    private fun select(scope: TestScope): StrokeInput =
        InputSelector(
            probe = { ProbeResult(true, "ok") },
            settings =
                object : InputSettings {
                    override val forceTouch = false
                },
            diagnostics = { log += "diag" },
            scope = scope,
            onyxFactory = { onyx },
            touchFactory = { SilentRawInput(log, "touch") },
            mainExecutor = Executor { it.run() },
        ).select()

    private fun stylusDown(
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
                },
            )
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun startOnActivity(scope: TestScope): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        select(scope).start(View(activity), board, listOf(tabBar))
        return activity
    }

    @Test
    fun pen_tap_on_the_toolbar_below_the_board_keeps_onyx() =
        runTest {
            val activity = startOnActivity(this)
            activity.window.callback.dispatchTouchEvent(stylusDown(50f, 150f))
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).doesNotContain("diag")
            assertThat(log).doesNotContain("touch:start")
        }

    @Test
    fun pen_tap_on_an_excluded_overlay_keeps_onyx() =
        runTest {
            val activity = startOnActivity(this)
            activity.window.callback.dispatchTouchEvent(stylusDown(50f, 5f))
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).doesNotContain("diag")
        }

    @Test
    fun pen_down_while_raw_drawing_is_off_keeps_onyx() =
        runTest {
            val activity = startOnActivity(this)
            onyx.rawActive = false
            activity.window.callback.dispatchTouchEvent(stylusDown(50f, 50f))
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).doesNotContain("diag")
        }

    @Test
    fun silent_raw_path_on_the_board_still_falls_back_to_touch() =
        runTest {
            val activity = startOnActivity(this)
            activity.window.callback.dispatchTouchEvent(stylusDown(50f, 50f))
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(log).containsAtLeast("diag", "onyx:stop", "touch:start").inOrder()
        }
}
