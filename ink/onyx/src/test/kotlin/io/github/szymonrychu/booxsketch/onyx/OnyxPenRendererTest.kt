package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.pen.NeoPenConfig
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what the renderer hands to the SDK pens. */
private class RecordingPens(
    private val failure: Throwable? = null,
) : OnyxPens {
    val drawn = mutableListOf<SdkStroke>()

    override fun draw(
        canvas: Canvas,
        stroke: SdkStroke,
    ) {
        failure?.let { throw it }
        drawn += stroke
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxPenRendererTest {
    private val canvas = Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))

    private fun stroke(
        tool: Tool,
        width: Float = 4f,
        points: List<Point> = listOf(Point(10f, 20f, 0.25f, 0), Point(30f, 40f, 0.75f, 16)),
        color: PenColor = PenColor.BLUE,
    ) = Stroke("s", tool, width, points, color)

    private fun drawnBy(
        tool: Tool,
        max: Float = 4096f,
        scale: Float = 2f,
    ): SdkStroke {
        val pens = RecordingPens()
        val drawn = OnyxPenRenderer(pens) { max }.draw(canvas, stroke(tool), scale, 5f, 7f)
        assertThat(drawn).isTrue()
        return pens.drawn.single()
    }

    @Test
    fun fountain_goes_to_the_sdk_fountain_pen_at_the_live_raw_width() {
        val s = drawnBy(Tool.FOUNTAIN)
        assertThat(s.pen).isEqualTo(SdkPen.FOUNTAIN)
        assertThat(s.width).isEqualTo(BrushMapping.width(Tool.FOUNTAIN, 4f * 2f))
    }

    @Test
    fun brush_goes_to_the_sdk_brush_pen_at_the_live_raw_width() {
        val s = drawnBy(Tool.BRUSH)
        assertThat(s.pen).isEqualTo(SdkPen.BRUSH)
        assertThat(s.width).isEqualTo(BrushMapping.width(Tool.BRUSH, 4f * 2f))
    }

    @Test
    fun charcoal_goes_to_the_sdk_charcoal_pen_at_the_live_raw_width() {
        val s = drawnBy(Tool.CHARCOAL)
        assertThat(s.pen).isEqualTo(SdkPen.CHARCOAL)
        assertThat(s.width).isEqualTo(BrushMapping.width(Tool.CHARCOAL, 4f * 2f))
    }

    @Test
    fun charcoal_v2_goes_to_the_sdk_charcoal_v2_pen_at_the_live_raw_width() {
        val s = drawnBy(Tool.CHARCOAL_V2)
        assertThat(s.pen).isEqualTo(SdkPen.CHARCOAL_V2)
        assertThat(s.width).isEqualTo(BrushMapping.width(Tool.CHARCOAL_V2, 4f * 2f))
        assertThat(s.width).isEqualTo(9.6f)
    }

    @Test
    fun points_are_canvas_pixels_with_raw_pressure_and_the_device_maximum() {
        val s = drawnBy(Tool.FOUNTAIN, max = 4096f, scale = 2f)
        assertThat(s.points.map { it.x }).containsExactly(25f, 65f).inOrder()
        assertThat(s.points.map { it.y }).containsExactly(47f, 87f).inOrder()
        assertThat(s.points.map { it.pressure }).containsExactly(1024f, 3072f).inOrder()
        assertThat(s.points.map { it.timestamp }).containsExactly(0L, 16L).inOrder()
        assertThat(s.points.map { it.size }).containsExactly(s.width, s.width)
        assertThat(s.maxPressure).isEqualTo(4096f)
        assertThat(s.color).isEqualTo(PenColor.BLUE.argb)
    }

    @Test
    fun an_unknown_device_maximum_uses_the_fallback_pressure_scale() {
        val s = drawnBy(Tool.BRUSH, max = 0f)
        assertThat(s.maxPressure).isEqualTo(OnyxStrokeInput.FALLBACK_MAX_PRESSURE)
        assertThat(s.points.first().pressure).isEqualTo(0.25f * OnyxStrokeInput.FALLBACK_MAX_PRESSURE)
    }

    @Test
    fun ballpoint_marker_and_shapes_are_left_to_the_app_renderer() {
        val pens = RecordingPens()
        val r = OnyxPenRenderer(pens) { 4096f }
        for (tool in listOf(Tool.BALLPOINT, Tool.MARKER, Tool.RECTANGLE, Tool.ARROW)) {
            assertThat(r.draw(canvas, stroke(tool), 1f, 0f, 0f)).isFalse()
        }
        assertThat(pens.drawn).isEmpty()
    }

    @Test
    fun a_single_point_is_left_to_the_app_renderer() {
        val pens = RecordingPens()
        val dot = stroke(Tool.FOUNTAIN, points = listOf(Point(1f, 1f, 1f, 0)))
        assertThat(OnyxPenRenderer(pens) { 4096f }.draw(canvas, dot, 1f, 0f, 0f)).isFalse()
        assertThat(pens.drawn).isEmpty()
    }

    @Test
    fun a_missing_native_pen_falls_back_for_good() {
        var calls = 0
        val pens =
            OnyxPens { _, _ ->
                calls++
                throw UnsatisfiedLinkError("no neopen_jni")
            }
        val r = OnyxPenRenderer(pens) { 4096f }
        assertThat(r.draw(canvas, stroke(Tool.CHARCOAL), 1f, 0f, 0f)).isFalse()
        assertThat(r.draw(canvas, stroke(Tool.CHARCOAL), 1f, 0f, 0f)).isFalse()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun the_real_sdk_without_its_native_library_falls_back_instead_of_crashing() {
        val r = OnyxPenRenderer()
        for (tool in listOf(Tool.FOUNTAIN, Tool.BRUSH, Tool.CHARCOAL, Tool.CHARCOAL_V2)) {
            assertThat(r.draw(canvas, stroke(tool), 1f, 0f, 0f)).isFalse()
        }
    }

    @Test
    fun charcoal_render_args_carry_pen_type_width_colour_pressure_scale_and_an_identity_matrix() {
        val s = drawnBy(Tool.CHARCOAL_V2)
        val args = SdkOnyxPens.charcoalArgs(canvas, s)
        assertThat(args.penType).isEqualTo(NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL_V2)
        assertThat(args.strokeWidth).isEqualTo(s.width)
        assertThat(args.color).isEqualTo(s.color)
        assertThat(args.createArgs.maxPressure).isEqualTo(s.maxPressure)
        assertThat(args.screenMatrix.isIdentity).isTrue()
        assertThat(args.isErase).isFalse()
        assertThat(args.points).hasSize(2)
        assertThat(args.canvas).isSameInstanceAs(canvas)
        assertThat(SdkOnyxPens.charcoalArgs(canvas, drawnBy(Tool.CHARCOAL)).penType)
            .isEqualTo(NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL)
    }

    @Test
    fun the_pen_paint_is_opaque_in_the_stroke_colour() {
        val p = SdkOnyxPens.paint(Color.RED)
        assertThat(p.color).isEqualTo(Color.RED)
        assertThat(p.isAntiAlias).isTrue()
    }
}
