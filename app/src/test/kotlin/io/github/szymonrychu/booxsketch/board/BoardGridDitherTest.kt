package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/**
 * The grid must survive the e-ink fast (handwriting) waveform, which dithers greys: frames posted while
 * raw drawing is live (the idle sync) go through it. A 4x4 ordered (Bayer) dither over the real
 * rendered grid must leave every row line with black pixels in every 8 px span of the text column,
 * whatever the line's y mod 4 is (device report on v0.14.0: lines vanished after a redraw, dots stayed).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BoardGridDitherTest {
    private val page = PageCanvas(1404, 1872, 300f)
    private val geo = NotebookGeometry(1404, 1872)

    private val bayer =
        arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5),
        )

    private fun render(
        g: BoardGeometry,
        w: Int,
        h: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        BoardGrid().draw(Canvas(bmp), g, page, null)
        return bmp
    }

    private fun ditheredBlack(
        bmp: Bitmap,
        x: Int,
        y: Int,
    ): Boolean {
        val lum = Color.red(bmp.getPixel(x, y))
        return lum < (bayer[y % 4][x % 4] + 0.5f) * 16f
    }

    private fun everyLineKeepsPixelsUnderDither(
        w: Int,
        h: Int,
    ) {
        val g = BoardGeometry(w, h, page)
        val bmp = render(g, w, h)
        val left = ceil(g.dx + geo.textLeft * g.scale).toInt() + 1
        val right = floor(g.dx + geo.marginX * g.scale).toInt() - 1
        for (k in 0 until geo.rowCount) {
            val v = g.dy + geo.rowLineY(k) * g.scale
            val ys = (floor(v).toInt() - 2)..(floor(v).toInt() + 2)
            var x = left
            while (x + SPAN <= right) {
                val kept = (x until x + SPAN).any { px -> ys.any { py -> ditheredBlack(bmp, px, py) } }
                assertWithMessage("line $k (view y $v) has no pixel in x $x..${x + SPAN} after dither").that(kept).isTrue()
                x += SPAN
            }
        }
    }

    @Test
    fun every_row_line_keeps_pixels_under_an_ordered_dither_in_portrait() = everyLineKeepsPixelsUnderDither(1860, 2480)

    @Test
    fun every_row_line_keeps_pixels_under_an_ordered_dither_in_landscape() = everyLineKeepsPixelsUnderDither(2480, 1860)

    private companion object {
        const val SPAN = 8
    }
}
