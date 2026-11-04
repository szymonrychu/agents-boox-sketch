package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardBackgroundTest {
    private val calls = mutableListOf<String>()
    private var frames = 0
    private val renderer =
        object : StrokeRenderer {
            override fun draw(
                canvas: Canvas,
                strokes: List<Stroke>,
                scale: Float,
                dx: Float,
                dy: Float,
            ) {
                strokes.forEach { calls += "stroke:${it.id}" }
            }
        }
    private val view =
        BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer).also { v ->
            v.canvasSource = {
                frames++
                val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
                object : Canvas(bitmap) {
                    override fun drawBitmap(
                        b: Bitmap,
                        matrix: Matrix,
                        paint: Paint?,
                    ) {
                        calls += "background"
                    }
                }
            }
            v.surfaceCreated(v.holder)
        }
    private val page = PageCanvas(10, 10, 1f)
    private val stroke = Stroke("s1", Tool.BALLPOINT, 2f, listOf(Point(1f, 1f, 0.5f, 0), Point(5f, 5f, 0.5f, 5)))
    private val bg = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

    @Test
    fun background_drawn_before_strokes() {
        calls.clear()
        view.show(page, listOf(stroke), 0, background = bg)
        assertThat(calls).containsExactly("background", "stroke:s1").inOrder()
    }

    @Test
    fun no_background_draws_as_today() {
        calls.clear()
        view.show(page, listOf(stroke), 0)
        assertThat(calls).containsExactly("stroke:s1")
    }

    @Test
    fun same_background_instance_does_not_redraw_or_force_full_refresh() {
        view.show(page, listOf(stroke), 0, background = bg)
        val before = frames
        view.show(page, listOf(stroke), 0, background = bg)
        assertThat(frames).isEqualTo(before)
    }

    @Test
    fun a_new_background_instance_counts_as_a_page_change() {
        view.show(page, listOf(stroke), 0, background = bg)
        val before = frames
        view.show(page, listOf(stroke), 0, background = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertThat(frames).isGreaterThan(before)
    }
}
