package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.render.StrokeRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/**
 * The handwriting update mode a touch stroke or a shape drag sets is near-binary, so it must not
 * outlive the stroke: it is reset before the frame that shows the committed result, and only then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EinkModeResetTest {
    /** Mode switches and frames in the order they happened: "fast", "normal", "frame". */
    private val log = mutableListOf<String>()
    private val renderer =
        object : StrokeRenderer {
            override fun draw(
                canvas: Canvas,
                strokes: List<Stroke>,
                scale: Float,
                dx: Float,
                dy: Float,
            ) = Unit
        }
    private val page = PageCanvas(10, 10, 1f)
    private val view =
        BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer).also { v ->
            v.canvasSource = {
                log += "frame"
                Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            }
            v.einkMode = { on -> log += if (on) "fast" else "normal" }
            v.surfaceCreated(v.holder)
        }

    private fun stroke(
        id: String,
        tool: Tool = Tool.BALLPOINT,
        vararg xs: Float,
    ) = Stroke(id, tool, 4f, xs.map { Point(it, it, 0.5f, 0L) })

    @Test
    fun a_touch_stroke_resets_the_mode_when_it_ends_and_the_commit_frame_is_drawn_after() {
        val a = stroke("a", Tool.BALLPOINT, 1f)
        view.show(page, listOf(a), 0)
        log.clear()
        view.showLive(stroke("live", Tool.BALLPOINT, 2f))
        view.showLive(stroke("live", Tool.BALLPOINT, 2f, 3f))
        assertThat(log).containsExactly("fast", "frame", "frame").inOrder()
        view.showLive(null)
        view.show(page, listOf(a, stroke("b", Tool.BALLPOINT, 2f, 3f)), 0)
        assertThat(log).containsExactly("fast", "frame", "frame", "normal", "frame").inOrder()
    }

    @Test
    fun clearing_the_live_stroke_resets_the_mode_without_a_frame_and_the_commit_draws_in_it() {
        view.show(page, emptyList(), 0)
        view.showLive(stroke("live", Tool.BALLPOINT, 2f))
        log.clear()
        view.showLive(null)
        assertThat(log).containsExactly("normal")
        view.show(page, listOf(stroke("b", Tool.BALLPOINT, 2f)), 0)
        assertThat(log).containsExactly("normal", "frame").inOrder()
    }

    @Test
    fun the_previous_commit_under_a_stroke_in_progress_keeps_the_fast_mode() {
        view.show(page, emptyList(), 0)
        view.showLive(stroke("live", Tool.BALLPOINT, 2f))
        log.clear()
        view.show(page, listOf(stroke("prev", Tool.BALLPOINT, 1f)), 0)
        assertThat(log).doesNotContain("normal")
        view.showLive(stroke("live", Tool.BALLPOINT, 2f, 3f))
        assertThat(log).doesNotContain("fast")
    }

    @Test
    fun a_shape_drag_resets_the_mode_before_the_frame_with_the_committed_shape() {
        view.show(page, emptyList(), 0)
        log.clear()
        view.show(page, emptyList(), 0, preview = stroke("p", Tool.RECTANGLE, 1f, 5f))
        view.show(page, emptyList(), 0, preview = stroke("p", Tool.RECTANGLE, 1f, 6f))
        assertThat(log).containsExactly("fast", "frame", "frame").inOrder()
        view.show(page, listOf(stroke("shape", Tool.RECTANGLE, 1f, 6f)), 0)
        assertThat(log).containsExactly("fast", "frame", "frame", "normal", "frame").inOrder()
    }

    @Test
    fun a_cancelled_drag_resets_the_mode_too() {
        view.show(page, emptyList(), 0)
        view.show(page, emptyList(), 0, preview = stroke("p", Tool.ARROW, 1f, 5f))
        log.clear()
        view.show(page, emptyList(), 0)
        assertThat(log).containsExactly("normal", "frame").inOrder()
    }

    @Test
    fun the_mode_is_left_alone_when_no_stroke_set_it() {
        view.show(page, emptyList(), 0)
        view.show(page, listOf(stroke("a", Tool.BALLPOINT, 1f)), 0)
        view.showLive(null)
        assertThat(log).doesNotContain("normal")
        assertThat(log).doesNotContain("fast")
    }
}
