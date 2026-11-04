package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoRenderPoint
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what the renderer hands to the SDK pens; [draws] is what the SDK reports. */
private class RecordingPens(
    private val failure: Throwable? = null,
    private val draws: Boolean = true,
) : OnyxPens {
    val drawn = mutableListOf<SdkStroke>()

    override fun draw(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean {
        failure?.let { throw it }
        drawn += stroke
        return draws
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

    @Test
    fun charcoal_pressure_comes_back_to_the_stored_0_to_1_after_the_one_java_division() {
        // NeoCharcoalPenWrapper divides each point by createArgs.maxPressure; the native pen never
        // reads maxTouchPressure, so this is the pressure the native charcoal sees.
        val s = drawnBy(Tool.CHARCOAL, max = 4096f)
        val args = SdkOnyxPens.charcoalArgs(canvas, s)
        assertThat(args.points.map { it.pressure / args.createArgs.maxPressure }).containsExactly(0.25f, 0.75f).inOrder()
    }

    @Test
    fun tilt_of_the_stored_points_reaches_the_sdk_pens() {
        val tilted = listOf(Point(10f, 20f, 0.25f, 0, tiltX = 30f, tiltY = -12f), Point(30f, 40f, 0.75f, 16, tiltX = 28f))
        val pens = RecordingPens()
        OnyxPenRenderer(pens) { 4096f }.draw(canvas, stroke(Tool.CHARCOAL, points = tilted), 1f, 0f, 0f)
        val s = pens.drawn.single()
        assertThat(s.tilt).isTrue()
        assertThat(s.points.map { it.tiltX }).containsExactly(30, 28).inOrder()
        assertThat(s.points.map { it.tiltY }).containsExactly(-12, 0).inOrder()
        assertThat(SdkOnyxPens.charcoalArgs(canvas, s).isTiltEnabled).isTrue()
    }

    @Test
    fun a_stroke_without_tilt_keeps_tilt_off() {
        val s = drawnBy(Tool.CHARCOAL)
        assertThat(s.tilt).isFalse()
        assertThat(s.points.map { it.tiltX }).containsExactly(0, 0)
        assertThat(SdkOnyxPens.charcoalArgs(canvas, s).isTiltEnabled).isFalse()
    }

    @Test
    fun a_stroke_the_sdk_drew_nothing_for_falls_back_and_the_next_one_still_tries() {
        val pens = RecordingPens(draws = false)
        val r = OnyxPenRenderer(pens) { 4096f }
        assertThat(r.draw(canvas, stroke(Tool.CHARCOAL), 1f, 0f, 0f)).isFalse()
        assertThat(r.draw(canvas, stroke(Tool.FOUNTAIN), 1f, 0f, 0f)).isFalse()
        assertThat(pens.drawn).hasSize(2)
    }

    @Test
    fun no_charcoal_render_points_means_nothing_drawn_and_the_textures_are_recycled() {
        val texture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertThat(SdkOnyxPens.drawTextures(canvas, emptyArray(), listOf(texture))).isFalse()
        assertThat(SdkOnyxPens.drawTextures(canvas, null, emptyList())).isFalse()
        assertThat(texture.isRecycled).isTrue()
    }

    @Test
    fun charcoal_render_points_draw_their_textures() {
        val target = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val texture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val drawn = SdkOnyxPens.drawTextures(Canvas(target), arrayOf(NeoRenderPoint.create(3f, 4f, 2f, 0)), listOf(texture))
        assertThat(drawn).isTrue()
        assertThat(texture.isRecycled).isTrue()
    }
}
