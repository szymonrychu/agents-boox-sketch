package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
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
}
