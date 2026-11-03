package io.github.szymonrychu.booxsketch.ink

import android.app.Activity
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

private class BracketInput(
    private val log: MutableList<String>,
    private val name: String,
    private val failRefresh: Boolean = false,
) : StrokeInput {
    override val events: Flow<StrokeEvent> = emptyFlow()

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

    override fun refresh(redraw: () -> Unit) {
        if (failRefresh) error("boom")
        log += "$name:off"
        redraw()
        log += "$name:on"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxFallbackLifecycleTest {
    private val log = mutableListOf<String>()

    @Before
    fun reset() = InputSelector.resetForTest()

    private fun select(onyx: StrokeInput): StrokeInput =
        InputSelector(
            probe = { ProbeResult(true, "ok") },
            settings =
                object : InputSettings {
                    override val forceTouch = false
                },
            diagnostics = { log += "diag" },
            scope = TestScope(),
            onyxFactory = { onyx },
            touchFactory = { BracketInput(log, "touch") },
            mainExecutor = Executor { it.run() },
        ).select()

    @Test
    fun refresh_is_delegated_to_the_onyx_input() {
        val input = select(BracketInput(log, "onyx"))
        input.start(View(ApplicationProvider.getApplicationContext()), Rect(0, 0, 10, 10), emptyList())
        input.refresh { log += "redraw" }
        assertThat(log).containsExactly("onyx:off", "redraw", "onyx:on").inOrder()
    }

    @Test
    fun a_failing_onyx_refresh_still_redraws_and_falls_back_to_touch() {
        val input = select(BracketInput(log, "onyx", failRefresh = true))
        input.start(View(ApplicationProvider.getApplicationContext()), Rect(0, 0, 10, 10), emptyList())
        input.refresh { log += "redraw" }
        input.refresh { log += "redraw2" }
        assertThat(log).containsExactly("diag", "redraw", "touch:off", "redraw2", "touch:on").inOrder()
    }

    @Test
    fun repeated_start_installs_one_window_wrapper_and_stop_restores_the_original() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = activity.window.callback
        val input = select(BracketInput(log, "onyx"))
        val v = View(activity)
        input.start(v, Rect(0, 0, 10, 10), emptyList())
        input.start(v, Rect(0, 0, 10, 10), emptyList())
        input.stop()
        assertThat(activity.window.callback).isSameInstanceAs(original)
    }
}
