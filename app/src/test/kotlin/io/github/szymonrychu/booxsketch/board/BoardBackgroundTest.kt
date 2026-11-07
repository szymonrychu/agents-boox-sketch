package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
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

    /** [calls] with the grid's line and point draws in between, for paint order checks. */
    private val ordered = mutableListOf<String>()
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
                strokes.forEach {
                    calls += "stroke:${it.id}"
                    ordered += "stroke:${it.id}"
                }
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
                        val name = if (b === text) "text" else "background"
                        calls += name
                        ordered += name
                    }

                    override fun drawLines(
                        pts: FloatArray,
                        offset: Int,
                        count: Int,
                        paint: Paint,
                    ) {
                        ordered += "grid"
                    }

                    override fun drawPoints(
                        pts: FloatArray,
                        offset: Int,
                        count: Int,
                        paint: Paint,
                    ) {
                        ordered += "grid"
                    }
                }
            }
            v.surfaceCreated(v.holder)
        }
    private val text = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
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

    @Test
    fun text_layer_under_strokes_over_grid() {
        val notebookPage = PageCanvas(1404, 1872, 300f)
        ordered.clear()
        view.show(notebookPage, listOf(stroke), 0, background = bg, textLayer = text)
        assertThat(ordered.distinct()).containsExactly("grid", "background", "text", "stroke:s1").inOrder()
    }

    @Test
    fun a_new_text_layer_redraws_without_a_page_change() {
        view.show(page, listOf(stroke), 0, textLayer = text)
        val before = frames
        view.show(page, listOf(stroke), 0, textLayer = text)
        assertThat(frames).isEqualTo(before)
        view.show(page, listOf(stroke), 0, textLayer = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertThat(frames).isGreaterThan(before)
    }

    @Test
    fun consumed_strokes_not_painted() {
        val consumed = Stroke("gone", Tool.BALLPOINT, 2f, listOf(Point(1f, 1f, 0.5f, 0), Point(5f, 5f, 0.5f, 5)))
        val n = Notebook(page, listOf(Page(listOf(stroke, consumed), false, consumed = setOf("gone"))), 0)
        calls.clear()
        view.show(page, shownStrokes(n), 0)
        assertThat(calls).containsExactly("stroke:s1")
    }
}
