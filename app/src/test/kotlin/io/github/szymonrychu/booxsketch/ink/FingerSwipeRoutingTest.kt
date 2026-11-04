package io.github.szymonrychu.booxsketch.ink

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.documents.SwipeDir
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

private class QuietRawInput :
    StrokeInput,
    RawActivitySource {
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null
    override val events: Flow<StrokeEvent> = emptyFlow()
    override val rawActive: Boolean = true

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = Unit

    override fun setColor(color: PenColor) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FingerSwipeRoutingTest {
    private val touch = TouchStrokeInput(newId = { "t" }, fingerEnabled = { false })
    private lateinit var activity: Activity
    private val swipes = mutableListOf<SwipeDir>()
    private lateinit var selector: InputSelector

    @Before
    fun reset() = InputSelector.resetForTest()

    private fun start(scope: kotlinx.coroutines.CoroutineScope): StrokeInput {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = View(activity)
        selector =
            InputSelector(
                probe = { ProbeResult(true, "ok") },
                settings =
                    object : InputSettings {
                        override val forceTouch = false
                    },
                diagnostics = { },
                scope = scope,
                onyxFactory = { QuietRawInput() },
                touchFactory = { touch },
                mainExecutor = Executor { it.run() },
            )
        selector.onFingerSwipe = { swipes += it }
        return selector.select().also { it.start(view, Rect(0, 0, 1000, 1000), emptyList()) }
    }

    private fun send(
        toolType: Int,
        action: Int,
        x: Float,
        y: Float,
        at: Long,
    ) {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    this.toolType = toolType
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
        val ev = MotionEvent.obtain(at, at, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        activity.window.callback.dispatchTouchEvent(ev)
    }

    private fun swipe(
        toolType: Int,
        fromX: Float,
        toX: Float,
    ) {
        val t = SystemClock.uptimeMillis()
        send(toolType, MotionEvent.ACTION_DOWN, fromX, 500f, t)
        send(toolType, MotionEvent.ACTION_MOVE, (fromX + toX) / 2, 500f, t + 50)
        send(toolType, MotionEvent.ACTION_UP, toX, 500f, t + 100)
    }

    @Test
    fun finger_swipe_under_raw_mode_reaches_callback() =
        runTest {
            val input = start(this)
            swipe(MotionEvent.TOOL_TYPE_FINGER, 800f, 300f)
            swipe(MotionEvent.TOOL_TYPE_FINGER, 300f, 800f)
            assertThat(swipes).containsExactly(SwipeDir.NEXT, SwipeDir.PREV).inOrder()
            assertThat(input.strokeInProgress).isFalse()
        }

    @Test
    fun pen_events_never_reach_swipe_callback() =
        runTest {
            start(this)
            swipe(MotionEvent.TOOL_TYPE_STYLUS, 800f, 300f)
            assertThat(swipes).isEmpty()
        }

    @Test
    fun finger_events_never_create_strokes_on_document() =
        runTest {
            val input = start(this)
            swipe(MotionEvent.TOOL_TYPE_FINGER, 800f, 300f)
            assertThat(input.inProgress.value).isNull()
            assertThat(touch.strokeInProgress).isFalse()
        }

    @Test
    fun without_a_callback_swipes_are_ignored() =
        runTest {
            start(this)
            selector.onFingerSwipe = null
            swipe(MotionEvent.TOOL_TYPE_FINGER, 800f, 300f)
            assertThat(swipes).isEmpty()
        }
}
