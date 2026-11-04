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

/** Records which strokes went through the full (vendor pen) draw and which through the cheap preview draw. */
private class PathRenderer : StrokeRenderer {
    val full = mutableListOf<String>()
    val preview = mutableListOf<String>()

    override fun draw(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        full += strokes.map { it.id }
    }

    override fun drawPreview(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        preview += strokes.map { it.id }
    }
}

/** The shape drag preview and the touch stroke in progress are redrawn per input event: never the vendor pens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PerFrameDrawTest {
    private val renderer = PathRenderer()
    private val view =
        BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer).also { v ->
            v.canvasSource = { Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) }
            v.surfaceCreated(v.holder)
        }
    private val page = PageCanvas(10, 10, 1f)

    private fun stroke(
        id: String,
        tool: Tool,
    ) = Stroke(id, tool, 4f, listOf(Point(1f, 1f, 0.5f, 0L), Point(8f, 8f, 0.5f, 10L)))

    @Test
    fun a_shape_drag_preview_uses_the_cheap_draw_and_committed_strokes_the_full_one() {
        view.show(page, listOf(stroke("page", Tool.CHARCOAL)), 0, preview = stroke("drag", Tool.RECTANGLE))
        assertThat(renderer.full).containsExactly("page")
        assertThat(renderer.preview).containsExactly("drag")
    }

    @Test
    fun the_stroke_in_progress_uses_the_cheap_draw() {
        view.show(page, emptyList(), 0)
        view.showLive(stroke("live", Tool.CHARCOAL))
        assertThat(renderer.preview).containsExactly("live")
        assertThat(renderer.full).doesNotContain("live")
    }
}
