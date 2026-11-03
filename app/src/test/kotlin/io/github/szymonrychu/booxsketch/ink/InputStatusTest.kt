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

private class QuietInput :
    StrokeInput,
    RawActivitySource {
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null
    override val events: Flow<StrokeEvent> = emptyFlow()
    override var rawActive = true

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = Unit
}

/** What the Diagnostics screen reports: the active input path, raw drawing on or off, and why touch was chosen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InputStatusTest {
    private val onyx = QuietInput()
    private var forceTouch = false

    @Before
    fun reset() = InputSelector.resetForTest()

    private fun selector(
        scope: TestScope,
        probe: ProbeResult = ProbeResult(true, "ok"),
    ) = InputSelector(
        probe = { probe },
        settings =
            object : InputSettings {
                override val forceTouch get() = this@InputStatusTest.forceTouch
            },
        diagnostics = { },
        scope = scope,
        onyxFactory = { onyx },
        touchFactory = { QuietInput() },
        mainExecutor = Executor { it.run() },
    )

    @Test
    fun nothing_is_reported_before_selection() =
        runTest {
            val s = selector(this)
            assertThat(s.status.value).isEqualTo(InputStatus(null, null))
            assertThat(s.rawEnabled()).isFalse()
        }

    @Test
    fun onyx_path_reports_raw_and_whether_raw_drawing_is_on() =
        runTest {
            val s = selector(this)
            s.select()
            assertThat(s.status.value).isEqualTo(InputStatus(InputPath.ONYX_RAW, null))
            assertThat(s.rawEnabled()).isTrue()
            onyx.rawActive = false
            assertThat(s.rawEnabled()).isFalse()
        }

    @Test
    fun failed_probe_reports_touch_with_the_probe_reason() =
        runTest {
            val s = selector(this, ProbeResult(false, "not an Onyx device"))
            s.select()
            assertThat(s.status.value.path).isEqualTo(InputPath.TOUCH_FALLBACK)
            assertThat(s.status.value.fallbackReason).contains("not an Onyx device")
            assertThat(s.rawEnabled()).isFalse()
        }

    @Test
    fun forced_touch_reports_the_setting() =
        runTest {
            forceTouch = true
            val s = selector(this)
            s.select()
            assertThat(s.status.value.path).isEqualTo(InputPath.TOUCH_FALLBACK)
            assertThat(s.status.value.fallbackReason).contains("Force touch")
        }

    @Test
    fun runtime_fallback_reports_touch_with_its_reason_also_for_a_later_selection() =
        runTest {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val s = selector(this)
            s.select().start(View(activity), Rect(0, 0, 100, 100), emptyList())
            val props = arrayOf(MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords =
                arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = 50f
                        y = 50f
                    },
                )
            val t = SystemClock.uptimeMillis()
            activity.window.callback.dispatchTouchEvent(
                MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0),
            )
            advanceTimeBy(InputSelector.RAW_CALLBACK_TIMEOUT_MS + 1)
            assertThat(s.status.value.path).isEqualTo(InputPath.TOUCH_FALLBACK)
            assertThat(s.status.value.fallbackReason).contains("no raw pen callback")
            assertThat(s.rawEnabled()).isFalse()
            val again = selector(this)
            again.select()
            assertThat(again.status.value.fallbackReason).contains("no raw pen callback")
        }
}
