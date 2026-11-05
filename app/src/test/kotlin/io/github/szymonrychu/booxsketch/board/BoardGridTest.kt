package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/** Records the grid lines drawn: (x1, y1, x2, y2) per line. */
private class LineRecorder : Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) {
    val lines = mutableListOf<List<Float>>()
    var paint: Paint? = null

    override fun drawLines(
        pts: FloatArray,
        offset: Int,
        count: Int,
        paint: Paint,
    ) {
        this.paint = paint
        for (i in offset until offset + count step 4) lines += listOf(pts[i], pts[i + 1], pts[i + 2], pts[i + 3])
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardGridTest {
    @Test
    fun pitch_is_five_millimetres_of_the_display_dpi() {
        assertThat(BoardGrid.pitchPx(254f)).isWithin(1e-4f).of(50f)
        assertThat(BoardGrid.pitchPx(320f)).isWithin(1e-3f).of(5f / 25.4f * 320f)
    }

    @Test
    fun lines_start_at_the_page_corner_and_stop_at_the_page_edge() {
        val grid = BoardGrid(10f, 20f)
        val c = LineRecorder()
        grid.draw(c, RectF(5f, 7f, 36f, 88f), null)
        val vertical = c.lines.filter { it[0] == it[2] }.map { it[0] }
        val horizontal = c.lines.filter { it[1] == it[3] }.map { it[1] }
        assertThat(vertical).containsExactly(5f, 15f, 25f, 35f).inOrder()
        assertThat(horizontal).containsExactly(7f, 27f, 47f, 67f, 87f).inOrder()
        c.lines.filter { it[0] == it[2] }.forEach { assertThat(it[1]).isEqualTo(7f) }
        c.lines.filter { it[0] == it[2] }.forEach { assertThat(it[3]).isEqualTo(88f) }
    }

    @Test
    fun only_lines_inside_the_clip_are_drawn() {
        val grid = BoardGrid(10f, 10f)
        val c = LineRecorder()
        grid.draw(c, RectF(0f, 0f, 100f, 100f), RectF(32f, 41f, 58f, 53f))
        val vertical = c.lines.filter { it[0] == it[2] }.map { it[0] }
        val horizontal = c.lines.filter { it[1] == it[3] }.map { it[1] }
        assertThat(vertical).containsExactly(40f, 50f).inOrder()
        assertThat(horizontal).containsExactly(50f)
        c.lines.filter { it[0] == it[2] }.forEach { assertThat(it[1]).isEqualTo(41f) }
        c.lines.filter { it[0] == it[2] }.forEach { assertThat(it[3]).isEqualTo(53f) }
    }

    @Test
    fun line_is_thin_lightgrey() {
        val c = LineRecorder()
        BoardGrid(10f, 10f).draw(c, RectF(0f, 0f, 30f, 30f), null)
        assertThat(c.paint!!.color).isEqualTo(BoardGrid.COLOR)
        assertThat(BoardGrid.COLOR).isEqualTo(0xFFD3D3D3.toInt())
        assertThat(c.paint!!.strokeWidth).isEqualTo(1f)
        assertThat(c.paint!!.isAntiAlias).isFalse()
    }

    @Test
    fun surface_paints_the_grid_before_the_ink_inside_the_dirty_clip_only() {
        val calls = mutableListOf<String>()
        val renderer =
            object : io.github.szymonrychu.booxsketch.render.StrokeRenderer {
                override fun draw(
                    canvas: Canvas,
                    strokes: List<io.github.szymonrychu.booxsketch.model.Stroke>,
                    scale: Float,
                    dx: Float,
                    dy: Float,
                ) {
                    calls += "ink"
                }
            }
        val view = BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer)
        var frames = 0
        val rec =
            object : Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) {
                override fun drawLines(
                    pts: FloatArray,
                    offset: Int,
                    count: Int,
                    paint: Paint,
                ) {
                    calls += "grid"
                }
            }
        view.canvasSource = {
            frames++
            rec
        }
        view.surfaceCreated(view.holder)
        calls.clear()
        view.show(
            PageCanvas(10, 10, 1f),
            listOf(
                io.github.szymonrychu.booxsketch.model
                    .Stroke("s", io.github.szymonrychu.booxsketch.model.Tool.BALLPOINT, 2f, emptyList()),
            ),
            0,
        )
        assertThat(calls.indexOf("grid")).isAtLeast(0)
        assertThat(calls.indexOf("grid")).isLessThan(calls.indexOf("ink"))
    }
}
