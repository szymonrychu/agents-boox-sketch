package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/** A stand-in for the native pens: a dot on every point plus grain [grain] widths below the first. */
private class DotPen(
    private val claims: Boolean = true,
    private val grain: Float = 0f,
    private val accepts: Boolean = true,
) : PenRenderer {
    var calls = 0

    override fun handles(stroke: Stroke) = accepts

    override fun draw(
        canvas: Canvas,
        stroke: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        calls++
        if (!claims) return false
        val paint = Paint().apply { color = stroke.color.argb }
        for (p in stroke.points) {
            canvas.drawRect(
                p.x * scale + dx - 1,
                p.y * scale + dy - 1,
                p.x * scale + dx + 1,
                p.y * scale + dy + 1,
                paint,
            )
        }
        val first = stroke.points.first()
        val gy = first.y * scale + dy + grain * stroke.width * scale
        canvas.drawRect(first.x * scale + dx - 1, gy - 1, first.x * scale + dx + 1, gy + 1, paint)
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CachedPenRendererTest {
    private val stroke =
        Stroke("c", Tool.CHARCOAL, 4f, listOf(Point(50f, 50f, 0.5f, 0), Point(80f, 50f, 0.5f, 16)), PenColor.RED)

    private fun board(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp).apply { drawColor(Color.WHITE) }
    }

    @Test
    fun the_pen_runs_once_per_stroke_and_redraws_blit_the_same_pixels() {
        val pen = DotPen()
        val r = CachedPenRenderer(pen)
        repeat(5) {
            val (bmp, c) = board()
            assertThat(r.draw(c, stroke, 1f, 10f, 20f)).isTrue()
            assertThat(bmp.getPixel(60, 70)).isEqualTo(PenColor.RED.argb)
            assertThat(bmp.getPixel(90, 70)).isEqualTo(PenColor.RED.argb)
            assertThat(bmp.getPixel(5, 5)).isEqualTo(Color.WHITE)
        }
        assertThat(pen.calls).isEqualTo(1)
    }

    @Test
    fun another_placement_or_stroke_instance_runs_the_pen_again() {
        val pen = DotPen()
        val r = CachedPenRenderer(pen)
        val (_, c) = board()
        r.draw(c, stroke, 1f, 0f, 0f)
        r.draw(c, stroke, 2f, 0f, 0f)
        r.draw(c, stroke, 1f, 3f, 0f)
        r.draw(c, stroke.copy(), 1f, 0f, 0f)
        assertThat(pen.calls).isEqualTo(4)
    }

    @Test
    fun a_declined_stroke_falls_back_every_time_without_running_the_pen_again() {
        val pen = DotPen(claims = false)
        val r = CachedPenRenderer(pen)
        val (bmp, c) = board()
        assertThat(r.draw(c, stroke, 1f, 0f, 0f)).isFalse()
        assertThat(r.draw(c, stroke, 1f, 0f, 0f)).isFalse()
        assertThat(pen.calls).isEqualTo(1)
        assertThat(bmp.getPixel(50, 50)).isEqualTo(Color.WHITE)
    }

    @Test
    fun charcoal_grain_three_and_a_half_widths_from_the_line_survives_the_cache() {
        val (bmp, c) = board()
        CachedPenRenderer(DotPen(grain = 3.5f)).draw(c, stroke, 1f, 0f, 0f)
        assertThat(bmp.getPixel(50, 64)).isEqualTo(PenColor.RED.argb)
    }

    @Test
    fun fountain_and_brush_ink_two_and_a_half_widths_from_the_line_survives_the_cache() {
        // The native pens size points from pressure and velocity past the nominal width; 1.5 widths clipped them.
        for (tool in listOf(Tool.FOUNTAIN, Tool.BRUSH)) {
            val (bmp, c) = board()
            CachedPenRenderer(DotPen(grain = 2.5f)).draw(c, stroke.copy(tool = tool), 1f, 0f, 0f)
            assertThat(bmp.getPixel(50, 60)).isEqualTo(PenColor.RED.argb)
        }
    }

    @Test
    fun fountain_and_brush_pad_wider_than_ballpoint_and_marker_and_narrower_than_charcoal() {
        for (tool in listOf(Tool.FOUNTAIN, Tool.BRUSH)) {
            assertThat(inkWidthFactor(tool)).isGreaterThan(inkWidthFactor(Tool.MARKER))
            assertThat(inkWidthFactor(tool)).isLessThan(inkWidthFactor(Tool.CHARCOAL))
            val b = inkBounds(stroke.copy(tool = tool), 1f, 0f, 0f)!!
            assertThat(b.top).isAtMost(50f - 3f * 4f)
        }
        assertThat(inkWidthFactor(Tool.BALLPOINT)).isEqualTo(inkWidthFactor(Tool.MARKER))
    }

    @Test
    fun a_right_to_left_stroke_is_rendered_by_the_pen_not_declined() {
        // Drawn right to left and upwards: its bounds once kept right and bottom at -Float.MAX, so the pen was declined.
        val back =
            Stroke(
                "b",
                Tool.BRUSH,
                4f,
                listOf(Point(150f, 150f, 0.5f, 0), Point(100f, 120f, 0.5f, 8), Point(40f, 60f, 0.5f, 16)),
                PenColor.RED,
            )
        val pen = DotPen()
        val r = CachedPenRenderer(pen)
        val (bmp, c) = board()
        assertThat(r.draw(c, back, 1f, 0f, 0f)).isTrue()
        assertThat(r.rendered).isEqualTo(1)
        for ((x, y) in listOf(150 to 150, 100 to 120, 40 to 60)) {
            assertThat(bmp.getPixel(x, y)).isEqualTo(PenColor.RED.argb)
        }
    }

    @Test
    fun a_full_cache_evicts_and_renders_again() {
        val pen = DotPen()
        val r = CachedPenRenderer(pen, maxBytes = 1)
        val (bmp, c) = board()
        r.draw(c, stroke, 1f, 0f, 0f)
        r.draw(c, stroke, 1f, 0f, 0f)
        assertThat(pen.calls).isEqualTo(2)
        assertThat(bmp.getPixel(50, 50)).isEqualTo(PenColor.RED.argb)
    }

    @Test
    fun the_board_and_document_views_draw_committed_strokes_through_the_cache() {
        val pen = DotPen()
        val renderer = CanvasStrokeRenderer(CachedPenRenderer(pen))
        val (_, c) = board()
        repeat(3) { renderer.draw(c, listOf(stroke), 1f, 0f, 0f) }
        renderer.drawPreview(c, listOf(stroke), 1f, 0f, 0f)
        assertThat(pen.calls).isEqualTo(1)
    }

    @Test
    fun a_stroke_the_pen_does_not_handle_allocates_no_bitmap_and_never_runs_the_pen() {
        val pen = DotPen(accepts = false)
        var allocations = 0
        val r =
            CachedPenRenderer(pen, 1 shl 20) { w, h ->
                allocations++
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
        val (_, c) = board()
        repeat(3) { assertThat(r.draw(c, stroke, 1f, 0f, 0f)).isFalse() }
        assertThat(allocations).isEqualTo(0)
        assertThat(pen.calls).isEqualTo(0)
    }

    @Test
    fun the_declined_set_is_capped() {
        val pen = DotPen(claims = false)
        val r = CachedPenRenderer(pen)
        val (_, c) = board()
        val strokes = List(300) { stroke.copy() }
        strokes.forEach { r.draw(c, it, 1f, 0f, 0f) }
        r.draw(c, strokes.first(), 1f, 0f, 0f)
        assertThat(pen.calls).isEqualTo(301)
    }

    @Test
    fun the_bitmap_is_clipped_to_the_canvas() {
        val wide = stroke.copy(width = 400f)
        val sizes = mutableListOf<Pair<Int, Int>>()
        val r =
            CachedPenRenderer(DotPen(), 1 shl 24) { w, h ->
                sizes += w to h
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
        val (bmp, c) = board()
        assertThat(r.draw(c, wide, 1f, 0f, 0f)).isTrue()
        assertThat(sizes.single().first).isAtMost(200)
        assertThat(sizes.single().second).isAtMost(200)
        assertThat(bmp.getPixel(50, 50)).isEqualTo(PenColor.RED.argb)
    }

    @Test
    fun a_stroke_wholly_outside_the_canvas_allocates_nothing() {
        var allocations = 0
        val r =
            CachedPenRenderer(DotPen(), 1 shl 20) { w, h ->
                allocations++
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
        val (_, c) = board()
        assertThat(r.draw(c, stroke, 1f, 1000f, 1000f)).isFalse()
        assertThat(allocations).isEqualTo(0)
    }

    @Test
    fun an_allocation_failure_falls_back_to_the_canvas_renderer() {
        val pen = DotPen()
        val r = CachedPenRenderer(pen, 1 shl 20) { _, _ -> throw OutOfMemoryError("test") }
        val (bmp, c) = board()
        CanvasStrokeRenderer(r).draw(c, listOf(stroke), 1f, 0f, 0f)
        assertThat(pen.calls).isEqualTo(0)
        assertThat(bmp.getPixel(65, 50)).isNotEqualTo(Color.WHITE)
    }
}
