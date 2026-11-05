package io.github.szymonrychu.booxsketch.render

import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.board.BoardGrid
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.StrokesCodec
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The board grid is a view-only background: it must never reach the uploaded PNG or the strokes JSON. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GridNotExportedTest {
    private val canvas = Canvas(1000, 1400, 300f)
    private val cfg = ConfigDto(1568, 1_000_000, 1_000_000, 10, 9_000_000, 200, 1)
    private val diagonal =
        Stroke("s", Tool.BALLPOINT, 6f, listOf(Point(100f, 100f, 0.8f, 0), Point(900f, 1300f, 0.8f, 10)))
    private val page = Page(listOf(diagonal), true)

    private fun blankCornerHasNoGrid(png: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
        // Top-right fifth of the image lies far from the diagonal: a grid would put lines there.
        for (y in 0 until bmp.height / 5) {
            for (x in bmp.width * 4 / 5 until bmp.width) {
                assertThat(bmp.getPixel(x, y) and 0xFFFFFF).isNotEqualTo(BoardGrid.LINE_COLOR and 0xFFFFFF)
                assertThat(bmp.getPixel(x, y) and 0xFFFFFF).isNotEqualTo(BoardGrid.DOT_COLOR and 0xFFFFFF)
                assertThat(bmp.getPixel(x, y) and 0xFFFFFF).isEqualTo(0xFFFFFF)
            }
        }
    }

    @Test
    fun png_export_has_no_grid() {
        val r = PngExporter(CanvasStrokeRenderer()).export(page, canvas, cfg) as ExportResult.Exported
        blankCornerHasNoGrid(r.png)
    }

    @Test
    fun page_raster_export_has_no_grid() {
        val bg =
            android.graphics.Bitmap.createBitmap(1000, 1400, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.WHITE)
            }
        val r = PageRasterExporter(CanvasStrokeRenderer()).export(bg, page, canvas, cfg) as ExportResult.Exported
        blankCornerHasNoGrid(r.png)
    }

    @Test
    fun strokes_json_has_no_grid() {
        assertThat(String(StrokesCodec.encode(canvas, page)).lowercase()).doesNotContain("grid")
    }
}
