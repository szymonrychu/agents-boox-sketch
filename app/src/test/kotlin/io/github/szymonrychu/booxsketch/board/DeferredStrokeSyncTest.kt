package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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

/** Records the ids of the strokes each full draw received. */
private class IdRenderer : StrokeRenderer {
    val drawn = mutableListOf<String>()

    override fun draw(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        drawn += strokes.map { it.id }
    }

    override fun drawPreview(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    ) = Unit
}

/**
 * A stroke committed while raw drawing is live is deferred and drawn by [BoardSurfaceView.syncCommitted]
 * inside its dirty rect, whatever direction it was drawn in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeferredStrokeSyncTest {
    private val renderer = IdRenderer()
    private val dirty = mutableListOf<Rect>()
    private val view =
        BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer).also { v ->
            v.layout(0, 0, SIZE, SIZE)
            v.canvasSource = { Canvas(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)) }
            v.dirtyCanvasSource = { _, r ->
                dirty += Rect(r)
                Canvas(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))
            }
            v.surfaceCreated(v.holder)
            v.rawLive = { true }
        }
    private val page = PageCanvas(SIZE, SIZE, 1f)

    private fun stroke(
        id: String,
        vararg xy: Float,
    ) = Stroke(id, Tool.BALLPOINT, 4f, xy.toList().chunked(2).map { (x, y) -> Point(x, y, 0.5f, 0L) })

    private fun syncs(s: Stroke) {
        view.show(page, emptyList(), 0)
        renderer.drawn.clear()
        view.show(page, listOf(s), 0)
        assertThat(renderer.drawn).isEmpty()
        view.syncCommitted()
        assertThat(dirty).hasSize(1)
        for (p in s.points) assertThat(dirty.single().contains(p.x.toInt(), p.y.toInt())).isTrue()
        assertThat(renderer.drawn).containsExactly(s.id)
    }

    @Test
    fun a_right_to_left_stroke_is_drawn_at_the_idle_sync() = syncs(stroke("rtl", 250f, 40f, 160f, 150f, 60f, 260f))

    @Test
    fun a_bottom_to_top_stroke_is_drawn_at_the_idle_sync() = syncs(stroke("btt", 60f, 260f, 160f, 150f, 250f, 40f))

    @Test
    fun a_dot_is_drawn_at_the_idle_sync() = syncs(stroke("dot", 150f, 150f))

    private companion object {
        const val SIZE = 300
    }
}
