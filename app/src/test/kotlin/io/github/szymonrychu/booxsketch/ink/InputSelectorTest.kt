package io.github.szymonrychu.booxsketch.ink

import android.graphics.Rect
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeInput(
    val name: String,
    private val failOn: String? = null,
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
    private val onyx = FakeInput("onyx")
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
    )

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
}
