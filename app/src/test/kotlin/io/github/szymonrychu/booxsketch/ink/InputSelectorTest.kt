package io.github.szymonrychu.booxsketch.ink

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
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
import kotlin.concurrent.thread

private class FakeInput(
    val name: String,
    private val failOn: String? = null,
    override val freezesUi: Boolean = false,
) : StrokeInput,
    RawActivitySource {
    val calls = mutableListOf<String>()
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null
    override val events: Flow<StrokeEvent> = emptyFlow()

    private fun call(c: String) {
        calls += c
        if (c == failOn) error("boom $c")
    }

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) = call("start")

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = call("setTool")

    override fun pause() = call("pause")

    override fun resume() = call("resume")

    override fun stop() = call("stop")

    override fun holdUi() = call("holdUi")

    override fun releaseUi() = call("releaseUi")
}

private class FakeSettings(
    override var forceTouch: Boolean = false,
) : InputSettings

private class FakeDiagnostics : OnyxDiagnostics {
    val reasons = mutableListOf<String>()

    override fun record(reason: String) {
        reasons += reason
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InputSelectorTest {
    private val view = View(ApplicationProvider.getApplicationContext())
    private val onyx = FakeInput("onyx", freezesUi = true)
    private val touch = FakeInput("touch")
    private val diag = FakeDiagnostics()
    private val settings = FakeSettings()

    @Before
    fun resetProcessState() = InputSelector.resetForTest()

    private fun selector(
        scope: TestScope,
        probe: ProbeResult = ProbeResult(true, "ok"),
        onyxInput: FakeInput = onyx,
    ) = InputSelector(
        probe = { probe },
        settings = settings,
        diagnostics = diag,
        scope = scope,
        onyxFactory = { onyxInput },
        touchFactory = { touch },
        mainExecutor = mainExecutor,
    )

    private val mainExecutor = Executor { it.run() }

    private fun stylusDown(): MotionEvent {
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
                    x = 1f
                    y = 1f
                },
            )
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    @Test
    fun ui_holds_reach_onyx_and_stop_after_the_fallback() =
        runTest {
            val picked = selector(this).select()
            assertThat(picked.freezesUi).isTrue()
            picked.start(view, Rect(0, 0, 10, 10), emptyList())
            picked.holdUi()
            picked.releaseUi()
            assertThat(onyx.calls).containsAtLeast("holdUi", "releaseUi").inOrder()
            onyx.onFailure!!(IllegalStateException("x"))
            picked.holdUi()
            assertThat(onyx.calls.count { it == "holdUi" }).isEqualTo(1)
            assertThat(touch.calls).doesNotContain("holdUi")
        }

    @Test
    fun selector_uses_touch_on_non_onyx() =
        runTest {
            val picked = selector(this, probe = ProbeResult(false, "not an Onyx device")).select()
            assertThat(picked).isSameInstanceAs(touch)
        }

    @Test
    fun selector_honours_force_touch() =
        runTest {
            settings.forceTouch = true
            assertThat(selector(this).select()).isSameInstanceAs(touch)
        }

    @Test
    fun onyx_is_used_when_probe_passes() =
        runTest {
            assertThat(selector(this).select()).isNotSameInstanceAs(touch)
        }

    @Test
    fun exception_disables_onyx_for_process() =
        runTest {
            val bad = FakeInput("onyx", failOn = "start")
            val input = selector(this, onyxInput = bad).select()
            input.start(view, Rect(0, 0, 10, 10), emptyList())
            assertThat(touch.calls).contains("start")
            assertThat(diag.reasons).hasSize(1)
            assertThat(diag.reasons[0]).contains("boom start")
            // a new selection in the same process no longer picks Onyx
            assertThat(selector(this).select()).isSameInstanceAs(touch)
        }

    @Test
    fun no_raw_callback_in_300ms_falls_back() =
        runTest {
            val input = selector(this).select()
            input.start(view, Rect(0, 0, 10, 10), emptyList())
            input.setTool(Tool.MARKER, 6f)
            (input as StylusDownObserver).onStylusDown()
            advanceTimeBy(299)
            assertThat(touch.calls).isEmpty()
            advanceTimeBy(2)
            assertThat(touch.calls).containsAtLeast("start", "setTool")
            assertThat(onyx.calls).contains("stop")
            assertThat(diag.reasons).hasSize(1)
        }

    @Test
    fun raw_callback_within_300ms_keeps_onyx() =
        runTest {
            val input = selector(this).select()
            input.start(view, Rect(0, 0, 10, 10), emptyList())
            (input as StylusDownObserver).onStylusDown()
            advanceTimeBy(100)
            onyx.onRawActivity!!.invoke()
            advanceTimeBy(1000)
            assertThat(touch.calls).isEmpty()
            assertThat(diag.reasons).isEmpty()
        }

    @Test
    fun stylus_down_through_window_callback_arms_the_watch() =
        runTest {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val input = selector(this).select()
            input.start(View(activity), Rect(0, 0, 10, 10), emptyList())
            activity.window.callback.dispatchTouchEvent(stylusDown())
            advanceTimeBy(301)
            assertThat(touch.calls).contains("start")
            assertThat(diag.reasons).hasSize(1)
        }

    @Test
    fun two_starts_install_one_window_wrapper() =
        runTest {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val original = activity.window.callback
            val input = selector(this).select()
            input.start(View(activity), Rect(0, 0, 10, 10), emptyList())
            val first = activity.window.callback
            input.start(View(activity), Rect(0, 0, 20, 20), emptyList())
            assertThat(activity.window.callback).isSameInstanceAs(first)
            assertThat(first).isNotSameInstanceAs(original)
            input.stop()
            assertThat(activity.window.callback).isSameInstanceAs(original)
        }

    @Test
    fun failure_from_background_thread_starts_touch_once_on_main_executor() =
        runTest {
            val queued = mutableListOf<Runnable>()
            val sel =
                InputSelector({ ProbeResult(true, "ok") }, settings, diag, this, { onyx }, { touch }, { queued += it })
            val input = sel.select()
            input.start(view, Rect(0, 0, 10, 10), emptyList())
            thread { onyx.onFailure!!.invoke(IllegalStateException("bg")) }.join()
            thread { onyx.onFailure!!.invoke(IllegalStateException("bg2")) }.join()
            assertThat(touch.calls).isEmpty()
            queued.forEach { it.run() }
            assertThat(queued).hasSize(1)
            assertThat(touch.calls.filter { it == "start" }).hasSize(1)
        }

    @Test
    fun linkage_error_from_factory_falls_back_to_touch() =
        runTest {
            val sel =
                InputSelector({ ProbeResult(true, "ok") }, settings, diag, this, { throw NoClassDefFoundError("x/Y") }, { touch })
            assertThat(sel.select()).isSameInstanceAs(touch)
            assertThat(diag.reasons.single()).contains("NoClassDefFoundError")
            assertThat(selector(this).select()).isSameInstanceAs(touch)
        }

    @Test
    fun late_failure_and_watch_after_stop_do_nothing() =
        runTest {
            val input = selector(this).select()
            input.start(view, Rect(0, 0, 10, 10), emptyList())
            (input as StylusDownObserver).onStylusDown()
            input.stop()
            advanceTimeBy(1000)
            onyx.onFailure!!.invoke(IllegalStateException("late"))
            assertThat(touch.calls).isEmpty()
            assertThat(diag.reasons).isEmpty()
        }
}
