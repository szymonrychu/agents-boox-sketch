package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.board.TextLayer
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageRasterExporterTest {
    private val exporter = PageRasterExporter(CanvasStrokeRenderer())

    private fun cfg(
        maxLong: Int = 1568,
        maxBytes: Int = 1_000_000,
    ) = ConfigDto(maxLong, maxBytes, 1_000_000, 10, 9_000_000, 20, 1)

    /** Left half red, right half blue. */
    private fun background(
        w: Int,
        h: Int,
    ) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        for (x in 0 until w) for (y in 0 until h) b.setPixel(x, y, if (x < w / 2) Color.RED else Color.BLUE)
    }

    private fun inked(canvas: Canvas) =
        Page(
            listOf(
                Stroke(
                    "s",
                    Tool.BALLPOINT,
                    12f,
                    listOf(Point(10f, canvas.h / 2f, 0.5f, 0), Point(canvas.w - 10f, canvas.h / 2f, 0.5f, 10)),
                ),
            ),
            true,
        )

    @Test
    fun raster_export_has_background_pixels_and_ink() {
        val canvas = Canvas(100, 200, 300f)
        val result = exporter.export(background(100, 200), inked(canvas), canvas, cfg()) as ExportResult.Exported
        val decoded = BitmapFactory.decodeByteArray(result.png, 0, result.png.size)
        assertThat(decoded.width).isEqualTo(100)
        assertThat(decoded.height).isEqualTo(200)
        assertThat(decoded.getPixel(5, 5)).isEqualTo(Color.RED)
        assertThat(decoded.getPixel(95, 5)).isEqualTo(Color.BLUE)
        val ink = decoded.getPixel(50, 100)
        assertThat(Color.red(ink) + Color.green(ink) + Color.blue(ink)).isLessThan(60)
    }

    @Test
    fun raster_export_caps_long_edge() {
        val canvas = Canvas(400, 800, 300f)
        val result = exporter.export(background(400, 800), inked(canvas), canvas, cfg(maxLong = 200)) as ExportResult.Exported
        assertThat(result.h).isEqualTo(200)
        assertThat(result.w).isEqualTo(100)
    }

    @Test
    fun a_page_without_ink_is_empty() {
        val canvas = Canvas(100, 200, 300f)
        assertThat(exporter.export(background(100, 200), Page(emptyList(), false), canvas, cfg())).isEqualTo(ExportResult.Empty)
    }

    @Test
    fun too_big_png_reports_too_big() {
        val canvas = Canvas(400, 800, 300f)
        val result = exporter.export(background(400, 800), inked(canvas), canvas, cfg(maxBytes = 10))
        assertThat(result).isInstanceOf(ExportResult.TooBig::class.java)
    }

    @Test
    fun notebook_page_png_has_text_and_no_grid() {
        val canvas = Canvas(1404, 1872, 300f)
        val g = NotebookGeometry(canvas.w, canvas.h)
        val stripped = SourceMap.stripWithColours("Hello notebook")
        val run =
            TextRun(
                "L1",
                1,
                1,
                2,
                53f,
                display = stripped.display,
                colourSpans = stripped.spans,
                sourceMap = stripped.map,
                sourceStrokeIds = listOf("gone"),
            )
        val ink = Stroke("s", Tool.BALLPOINT, 8f, listOf(Point(200f, 1200f, 0.5f, 0), Point(900f, 1200f, 0.5f, 10)))
        val consumed = Stroke("gone", Tool.BALLPOINT, 8f, listOf(Point(200f, 600f, 0.5f, 0), Point(900f, 600f, 0.5f, 10)))
        val page = Page(listOf(ink, consumed), true, text = listOf(run), consumed = setOf("gone"))
        val layer = TextLayer(g).render(page, null)
        val r = exporter.export(layer, page, canvas, cfg(maxLong = 1872)) as ExportResult.Exported
        val png = BitmapFactory.decodeByteArray(r.png, 0, r.png.size)
        assertThat(png.width).isEqualTo(1404)
        // Text in row 2's text band, ink at y 1200, the consumed stroke at y 600 absent.
        val textBand = (g.textBandTop(2) until g.rowLineY(2)).any { y -> (60 until 400).any { x -> png.getPixel(x, y) != Color.WHITE } }
        assertThat(textBand).isTrue()
        assertThat(png.getPixel(500, 1200)).isNotEqualTo(Color.WHITE)
        assertThat(png.getPixel(500, 600)).isEqualTo(Color.WHITE)
        // No grid: a row line of the text column would be grey at x 700.
        assertThat(png.getPixel(700, g.rowLineY(5))).isEqualTo(Color.WHITE)
    }
}
