package io.github.szymonrychu.booxsketch.ink

import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.board.InputGate
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.ui.PauseInputWhile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Recorder : StrokeInput {
    val calls = mutableListOf<String>()
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

    override fun pause() {
        calls += "pause"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun stop() = Unit
}

/**
 * The POST_NOTIFICATIONS system dialog pauses and resumes the activity (the board's [InputGate]);
 * the send outcome dialog pauses the same input ([PauseInputWhile]). Neither may un-pause the
 * input under the other, and raw drawing must come back once both are gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CountedPauseTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun only_the_last_resume_resumes_the_input() {
        val rec = Recorder()
        val input = CountedPause(rec)
        input.pause()
        input.pause()
        input.resume()
        assertThat(rec.calls).containsExactly("pause")
        input.resume()
        input.resume() // unbalanced extra resume is ignored
        assertThat(rec.calls).containsExactly("pause", "resume").inOrder()
    }

    @Test
    fun permission_dialog_then_resume_gives_raw_drawing_back() {
        val rec = Recorder()
        val gate = InputGate(CountedPause(rec))
        gate.setResumed(false)
        gate.setFocused(false)
        gate.setResumed(true)
        gate.setFocused(true)
        assertThat(rec.calls).containsExactly("pause", "resume").inOrder()
    }

    @Test
    fun outcome_dialog_stays_paused_when_the_permission_dialog_resumes_underneath() {
        val rec = Recorder()
        val input = CountedPause(rec)
        val gate = InputGate(input)
        var open by mutableStateOf(false)
        compose.setContent { PauseInputWhile(open, input) }
        // Send: the permission dialog pauses the activity, the outcome dialog opens meanwhile.
        gate.setResumed(false)
        open = true
        compose.waitForIdle()
        gate.setResumed(true)
        assertThat(rec.calls).containsExactly("pause")
        open = false
        compose.waitForIdle()
        assertThat(rec.calls).containsExactly("pause", "resume").inOrder()
    }

    @Test
    fun permission_dialog_over_the_outcome_dialog_does_not_leave_input_paused() {
        val rec = Recorder()
        val input = CountedPause(rec)
        val gate = InputGate(input)
        var open by mutableStateOf(true)
        compose.setContent { PauseInputWhile(open, input) }
        compose.waitForIdle()
        gate.setResumed(false)
        open = false
        compose.waitForIdle()
        assertThat(rec.calls).containsExactly("pause")
        gate.setResumed(true)
        assertThat(rec.calls).containsExactly("pause", "resume").inOrder()
    }
}
