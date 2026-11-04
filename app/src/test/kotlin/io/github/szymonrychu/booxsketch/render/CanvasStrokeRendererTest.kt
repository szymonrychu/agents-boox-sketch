package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.PenRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CanvasStrokeRendererTest {
    private fun render(
        s: Stroke,
        scale: Float = 1f,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        CanvasStrokeRenderer().draw(c, listOf(s), scale, 0f, 0f)
        return bmp
    }

    private fun line(
        tool: Tool,
        color: PenColor,
        width: Float = 10f,
    ) = Stroke(
        "s",
        tool,
        width,
        listOf(Point(50f, 200f, 1f, 0), Point(350f, 200f, 1f, 1)),
        color,
    )

    @Test
    fun red_ballpoint_pixels_are_red() {
        val px = render(line(Tool.BALLPOINT, PenColor.RED)).getPixel(200, 200)
        assertThat(px).isEqualTo(PenColor.RED.argb)
    }

    @Test
    fun marker_is_tinted_and_translucent() {
        val px = render(line(Tool.MARKER, PenColor.RED)).getPixel(200, 200)
        // red is 0xE00000: translucent over white it stays red-dominant but is no longer opaque red
        assertThat(px).isNotEqualTo(PenColor.RED.argb)
        assertThat(Color.red(px)).isGreaterThan(Color.green(px))
        assertThat(Color.green(px)).isGreaterThan(0)
        assertThat(Color.green(px)).isLessThan(255)
        assertThat(Color.blue(px)).isEqualTo(Color.green(px))
    }

    @Test
    fun rectangle_outline_drawn_interior_white() {
        val s =
            Stroke(
                "r",
                Tool.RECTANGLE,
                6f,
                listOf(Point(100f, 100f, 1f, 0), Point(300f, 300f, 1f, 1)),
                PenColor.BLUE,
            )
        val bmp = render(s)
        assertThat(bmp.getPixel(200, 100)).isEqualTo(PenColor.BLUE.argb)
        assertThat(bmp.getPixel(100, 200)).isEqualTo(PenColor.BLUE.argb)
        assertThat(bmp.getPixel(200, 200)).isEqualTo(Color.WHITE)
    }

    @Test
    fun arrow_shaft_and_head_drawn() {
        val s =
            Stroke(
                "a",
                Tool.ARROW,
                6f,
                listOf(Point(50f, 200f, 1f, 0), Point(350f, 200f, 1f, 1)),
                PenColor.GREEN,
            )
        val bmp = render(s)
        assertThat(bmp.getPixel(150, 200)).isEqualTo(PenColor.GREEN.argb)
        // a wing runs back from the tip at 28 degrees: about 20 px behind and 10 px off the shaft
        val wing = (330..345).any { x -> (185..199).any { y -> bmp.getPixel(x, y) == PenColor.GREEN.argb } }
        assertThat(wing).isTrue()
        assertThat(bmp.getPixel(150, 150)).isEqualTo(Color.WHITE)
    }

    @Test
    fun black_default_unchanged() {
        val s = Stroke("b", Tool.BALLPOINT, 10f, listOf(Point(50f, 200f, 1f, 0), Point(350f, 200f, 1f, 1)))
        assertThat(render(s).getPixel(200, 200)).isEqualTo(Color.BLACK)
    }

    @Test
    fun rectangle_stroke_width_scales_with_canvas() {
        val s =
            Stroke(
                "r",
                Tool.RECTANGLE,
                6f,
                listOf(Point(50f, 50f, 1f, 0), Point(150f, 150f, 1f, 1)),
                PenColor.BLUE,
            )
        val bmp = render(s, 2f)
        // top edge at y=100, 12 px thick: 94..105
        assertThat(bmp.getPixel(200, 95)).isEqualTo(PenColor.BLUE.argb)
        assertThat(bmp.getPixel(200, 104)).isEqualTo(PenColor.BLUE.argb)
        assertThat(bmp.getPixel(200, 90)).isEqualTo(Color.WHITE)
        assertThat(bmp.getPixel(200, 108)).isEqualTo(Color.WHITE)
    }

    @Test
    fun arrow_shaft_width_scales_with_canvas() {
        val s =
            Stroke(
                "a",
                Tool.ARROW,
                6f,
                listOf(Point(25f, 100f, 1f, 0), Point(175f, 100f, 1f, 1)),
                PenColor.GREEN,
            )
        val bmp = render(s, 2f)
        // shaft at y=200, 12 px thick: 194..205
        assertThat(bmp.getPixel(150, 195)).isEqualTo(PenColor.GREEN.argb)
        assertThat(bmp.getPixel(150, 204)).isEqualTo(PenColor.GREEN.argb)
        assertThat(bmp.getPixel(150, 190)).isEqualTo(Color.WHITE)
        assertThat(bmp.getPixel(150, 208)).isEqualTo(Color.WHITE)
    }

    private class RecordingPen(
        private val claims: Boolean,
    ) : PenRenderer {
        val offered = mutableListOf<Tool>()

        override fun draw(
            canvas: Canvas,
            stroke: Stroke,
            scale: Float,
            dx: Float,
            dy: Float,
        ): Boolean {
            offered += stroke.tool
            return claims
        }
    }

    private fun renderWith(
        pen: PenRenderer,
        s: Stroke,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        CanvasStrokeRenderer(pen).draw(c, listOf(s), 1f, 0f, 0f)
        return bmp
    }

    @Test
    fun a_stroke_the_vendor_pen_draws_is_not_drawn_again_by_the_fallback() {
        val pen = RecordingPen(claims = true)
        val bmp = renderWith(pen, line(Tool.CHARCOAL, PenColor.BLACK))
        assertThat(pen.offered).containsExactly(Tool.CHARCOAL)
        assertThat(bmp.getPixel(200, 200)).isEqualTo(Color.WHITE)
    }

    @Test
    fun a_stroke_the_vendor_pen_declines_is_drawn_by_the_fallback() {
        val pen = RecordingPen(claims = false)
        val bmp = renderWith(pen, line(Tool.BALLPOINT, PenColor.RED))
        assertThat(pen.offered).containsExactly(Tool.BALLPOINT)
        assertThat(bmp.getPixel(200, 200)).isEqualTo(PenColor.RED.argb)
    }

    @Test
    fun shapes_are_never_offered_to_the_vendor_pen() {
        val pen = RecordingPen(claims = true)
        val rect = Stroke("r", Tool.RECTANGLE, 6f, listOf(Point(50f, 50f, 1f, 0), Point(150f, 150f, 1f, 1)), PenColor.BLUE)
        val bmp = renderWith(pen, rect)
        assertThat(pen.offered).isEmpty()
        assertThat(bmp.getPixel(100, 50)).isEqualTo(PenColor.BLUE.argb)
    }
}
