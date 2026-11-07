package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RegionCropperTest {
    private val canvas = PageCanvas(1404, 1872, 300f)
    private val cfg = ConfigDto(1568, 6_291_456, 2_097_152, 4, 8_388_608, 200, 1)
    private val region = Stroke("r", Tool.IMAGE_REGION, 3f, listOf(Point(200f, 300f, 1f, 0), Point(600f, 700f, 1f, 1)))

    /** A black ballpoint line across the middle of the region, and one outside it. */
    private val inside = Stroke("a", Tool.BALLPOINT, 8f, listOf(Point(250f, 500f, 1f, 0), Point(550f, 500f, 1f, 1)), PenColor.BLACK)
    private val outside = Stroke("b", Tool.BALLPOINT, 8f, listOf(Point(800f, 900f, 1f, 0), Point(1000f, 900f, 1f, 1)), PenColor.BLUE)

    private fun textLayer(fill: (Canvas) -> Unit): Bitmap =
        Bitmap.createBitmap(canvas.w, canvas.h, Bitmap.Config.ARGB_8888).also { fill(Canvas(it)) }

    private fun decode(png: ByteArray): Bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)

    @Test
    fun crop_has_ink_and_text_no_grid_no_frame() {
        val text = textLayer { c -> c.drawRect(300f, 350f, 400f, 400f, Paint().apply { color = PenColor.RED.argb }) }
        val png = RegionCropper(CanvasStrokeRenderer()).crop(Page(listOf(region, inside, outside), true), region, text, canvas, cfg)!!
        val bmp = decode(png)
        // Canvas resolution: the region's own size.
        assertThat(bmp.width).isEqualTo(400)
        assertThat(bmp.height).isEqualTo(400)
        // The ink at (400, 500) is at (200, 200) of the crop; the text at (350, 375) at (150, 75).
        assertThat(bmp.getPixel(200, 200)).isEqualTo(Color.BLACK)
        assertThat(bmp.getPixel(150, 75)).isEqualTo(PenColor.RED.argb)
        // No frame: the border is white, away from the ink line; no grid dots anywhere off the ink and text.
        for (x in 0 until 400) {
            assertThat(bmp.getPixel(x, 0)).isEqualTo(Color.WHITE)
            assertThat(bmp.getPixel(x, 399)).isEqualTo(Color.WHITE)
        }
        assertThat(bmp.getPixel(20, 300)).isEqualTo(Color.WHITE)
        assertThat(bmp.getPixel(0, 0)).isEqualTo(Color.WHITE)
    }

    @Test
    fun crop_shrinks_under_cap() {
        val random = Random(7)
        val noise =
            textLayer { c ->
                val p = Paint()
                for (y in 300 until 700 step 2) {
                    for (x in 200 until 600 step 2) {
                        p.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                        c.drawRect(x.toFloat(), y.toFloat(), x + 2f, y + 2f, p)
                    }
                }
            }
        val cap = 120_000
        val png =
            RegionCropper(
                CanvasStrokeRenderer(),
            ).crop(Page(listOf(region, inside), true), region, noise, canvas, cfg.copy(maxPngBytes = cap))!!
        assertThat(png.size).isAtMost(cap)
        val bmp = decode(png)
        assertThat(bmp.width).isLessThan(400)
        // Aspect kept.
        assertThat(bmp.width).isEqualTo(bmp.height)
    }

    @Test
    fun text_layer_at_half_resolution_is_scaled_to_the_canvas() {
        val half =
            Bitmap.createBitmap(canvas.w / 2, canvas.h / 2, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawRect(150f, 175f, 200f, 200f, Paint().apply { color = PenColor.RED.argb })
            }
        val bmp = decode(RegionCropper(CanvasStrokeRenderer()).crop(Page(listOf(region), true), region, half, canvas, cfg)!!)
        // The layer rect (300, 350) to (400, 400) on the canvas is (100, 50) to (200, 100) of the crop.
        assertThat(bmp.getPixel(150, 75)).isEqualTo(PenColor.RED.argb)
        assertThat(bmp.getPixel(50, 75)).isEqualTo(Color.WHITE)
    }

    @Test
    fun an_applied_region_frame_is_not_in_the_crop() {
        val other = Stroke("o", Tool.IMAGE_REGION, 3f, listOf(Point(150f, 250f, 1f, 0), Point(650f, 750f, 1f, 1)))
        val page = Page(listOf(region, other, inside), true, appliedRegions = mapOf("o" to "reg-o"))
        val bmp = decode(RegionCropper(CanvasStrokeRenderer()).crop(page, region, null, canvas, cfg)!!)
        for (x in 0 until bmp.width) assertThat(bmp.getPixel(x, 0)).isEqualTo(Color.WHITE)
        assertThat(bmp.getPixel(200, 200)).isEqualTo(Color.BLACK)
    }

    @Test
    fun crop_that_cannot_fit_is_null() {
        val png =
            RegionCropper(
                CanvasStrokeRenderer(),
            ).crop(Page(listOf(region, inside), true), region, null, canvas, cfg.copy(maxPngBytes = 10))
        assertThat(png).isNull()
    }
}
