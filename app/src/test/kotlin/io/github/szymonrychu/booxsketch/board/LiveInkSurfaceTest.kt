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

/** One renderer call: the strokes and the view mapping they were drawn with. */
private data class Drawn(
    val strokes: List<Stroke>,
    val scale: Float,
    val dx: Float,
    val dy: Float,
)

private class RecordingRenderer : StrokeRenderer {
    val frames = mutableListOf<MutableList<Drawn>>()

    override fun draw(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        frames.last() += Drawn(strokes, scale, dx, dy)
    }
}

/** The touch fallback has no vendor ink: the board surface itself must show the stroke being drawn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveInkSurfaceTest {
    private val renderer = RecordingRenderer()
    private val view =
        BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer).also { v ->
            v.canvasSource = {
                renderer.frames += mutableListOf<Drawn>()
                Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            }
            v.surfaceCreated(v.holder)
        }

    private fun stroke(
        id: String,
        vararg xs: Float,
    ) = Stroke(id, Tool.BALLPOINT, 4f, xs.map { Point(it, it, 0.5f, 0L) })

    @Test
    fun stroke_in_progress_is_drawn_on_the_board_in_view_pixels() {
        val page = stroke("page", 1f)
        view.show(PageCanvas(10, 10, 1f), listOf(page), 0)
        view.showLive(stroke("live", 2f))
        view.showLive(stroke("live", 2f, 3f))
        val last = renderer.frames.last()
        assertThat(last.flatMap { it.strokes }.map { it.id }).containsExactly("page", "live").inOrder()
        val live = last.single { d -> d.strokes.any { it.id == "live" } }
        assertThat(live.strokes.single().points).hasSize(2)
        assertThat(Triple(live.scale, live.dx, live.dy)).isEqualTo(Triple(1f, 0f, 0f))
    }

    @Test
    fun clearing_the_live_stroke_waits_for_the_committed_page_redraw() {
        view.showLive(stroke("live", 2f))
        val frames = renderer.frames.size
        view.showLive(null)
        assertThat(renderer.frames).hasSize(frames)
        view.show(PageCanvas(10, 10, 1f), listOf(stroke("page", 2f)), 0)
        assertThat(
            renderer.frames
                .last()
                .flatMap { it.strokes }
                .map { it.id },
        ).containsExactly("page")
    }
}
