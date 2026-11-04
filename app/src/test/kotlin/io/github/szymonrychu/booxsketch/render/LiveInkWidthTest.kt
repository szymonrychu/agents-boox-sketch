package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.board.BoardGeometry
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.BrushMapping
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/** The width given to the raw input is the on-screen width the renderer redraws the committed stroke with. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LiveInkWidthTest {
    private fun redrawnThickness(
        tool: Tool,
        width: Float,
        page: PageCanvas,
    ): Int {
        val g = BoardGeometry(400, 400, page)
        // What the input commits: view pixels, the selected width, full pressure.
        val live = Stroke("s", tool, width, listOf(Point(50f, 200f, 1f, 0), Point(350f, 200f, 1f, 1)), PenColor.BLACK)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        CanvasStrokeRenderer().draw(c, listOf(g.toCanvas(live)), g.scale, g.dx, g.dy)
        return (0 until 400).count { bmp.getPixel(200, it) != Color.WHITE }
    }

    @Test
    fun raw_brush_width_equals_redrawn_width_for_every_pen_and_scale() {
        for (page in listOf(PageCanvas(400, 400, 160f), PageCanvas(200, 200, 160f), PageCanvas(800, 800, 160f))) {
            for (tool in Tool.entries.filterNot { it.isShape }) {
                val raw = BrushMapping.width(tool, 10f)
                assertThat(redrawnThickness(tool, 10f, page).toFloat()).isWithin(1f).of(raw)
            }
        }
    }
}
