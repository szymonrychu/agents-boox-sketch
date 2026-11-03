package io.github.szymonrychu.booxsketch.ui

import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingInput : StrokeInput {
    val calls = mutableListOf<String>()
    override val events: Flow<StrokeEvent> = emptyFlow()

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        calls += "start"
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        calls += "setTool"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun stop() {
        calls += "stop"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PauseInputWhileTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun input_is_paused_while_open_and_resumed_after() {
        val input = RecordingInput()
        var open by mutableStateOf(false)
        compose.setContent { PauseInputWhile(open, input) }
        assertThat(input.calls).isEmpty()
        open = true
        compose.waitForIdle()
        assertThat(input.calls).containsExactly("pause")
        open = false
        compose.waitForIdle()
        assertThat(input.calls).containsExactly("pause", "resume").inOrder()
    }
}
