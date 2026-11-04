package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

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
    ) = Unit

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
class RawDrawingWiringTest {
    private fun stroke(id: String) = Stroke(id, Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))

    @Test
    fun exclude_rects_are_made_relative_to_the_board_and_clipped_to_it() {
        // board at (100, 50) in the window, 400x300; toolbar below overlapping 20 px, panel outside
        val rects =
            excludeRectsInView(
                viewLeft = 100,
                viewTop = 50,
                viewW = 400,
                viewH = 300,
                inWindow = listOf(Rect(100, 330, 500, 400), Rect(600, 0, 800, 600), Rect(90, 40, 130, 70)),
            )
        assertThat(rects).containsExactly(Rect(0, 280, 400, 300), Rect(0, 0, 30, 20)).inOrder()
    }

    @Test
    fun gate_pauses_on_pause_or_focus_loss_and_resumes_only_when_both_are_back() {
        val input = RecordingInput()
        val gate = InputGate(input)
        gate.setResumed(true)
        gate.setFocused(false)
        gate.setResumed(false)
        gate.setFocused(true)
        gate.setResumed(true)
        assertThat(input.calls).containsExactly("pause", "resume").inOrder()
    }

    @Test
    fun full_refresh_follows_load_page_switch_and_clear_only() {
        val a = listOf(stroke("a"))
        val ab = a + stroke("b")
        assertThat(needsFullRefresh(null, emptyList(), 0, a)).isTrue()
        assertThat(needsFullRefresh(0, a, 0, ab)).isFalse()
        assertThat(needsFullRefresh(0, ab, 0, a)).isFalse()
        assertThat(needsFullRefresh(0, ab, 1, emptyList())).isTrue()
        assertThat(needsFullRefresh(1, ab, 1, emptyList())).isTrue()
        assertThat(needsFullRefresh(1, emptyList(), 1, emptyList())).isFalse()
    }

    @Test
    fun surface_redraws_run_inside_the_input_refresh_and_full_refresh_is_debounced() {
        val log = mutableListOf<String>()
        val v = BoardSurfaceView(ApplicationProvider.getApplicationContext())
        v.refresher = { block ->
            log += "begin"
            block()
            log += "end"
        }
        v.surfaceCreated(v.holder)
        log.clear()
        val canvas = PageCanvas(100, 100, 300f)
        v.show(canvas, listOf(stroke("a")), 0)
        v.show(canvas, emptyList(), 1)
        v.show(canvas, emptyList(), 1)
        assertThat(log).containsExactly("begin", "end", "begin", "end").inOrder()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BoardSurfaceView.FULL_REFRESH_DELAY_MS))
        assertThat(log).hasSize(6)
    }

    @Test
    fun surface_lifecycle_reaches_the_callbacks() {
        val log = mutableListOf<String>()
        val v = BoardSurfaceView(ApplicationProvider.getApplicationContext())
        v.onSurfaceCreated = { log += "created" }
        v.onSurfaceDestroyed = { log += "destroyed" }
        v.onFocus = { log += "focus:$it" }
        v.surfaceCreated(v.holder)
        v.onWindowFocusChanged(false)
        v.surfaceDestroyed(v.holder)
        assertThat(log).containsExactly("created", "focus:false", "destroyed").inOrder()
    }

    @Test
    fun input_is_only_started_while_the_surface_exists() {
        val input = RecordingInput()
        val v = BoardSurfaceView(ApplicationProvider.getApplicationContext())
        v.layout(0, 0, 100, 100)
        v.startInput(input, emptyList())
        v.surfaceCreated(v.holder)
        v.startInput(input, emptyList())
        v.surfaceDestroyed(v.holder)
        v.startInput(input, emptyList())
        v.startInput(input, listOf(Rect(0, 0, 10, 10)))
        v.surfaceCreated(v.holder)
        v.startInput(input, emptyList())
        assertThat(input.calls).containsExactly("start", "start").inOrder()
    }
}
