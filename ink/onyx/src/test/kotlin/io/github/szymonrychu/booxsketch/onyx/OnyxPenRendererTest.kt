package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
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
import org.robolectric.shadows.ShadowLog

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

    private fun renderer(
        pens: OnyxPens,
        params: (Int) -> FloatArray? = { null },
        origin: PointF = PointF(),
    ) = OnyxPenRenderer(pens, params) { origin }

    private fun drawnBy(
        tool: Tool,
        scale: Float = 2f,
        params: (Int) -> FloatArray? = { null },
        points: List<Point> = listOf(Point(10f, 20f, 0.25f, 0), Point(30f, 40f, 0.75f, 16)),
        origin: PointF = PointF(),
    ): SdkStroke {
        val pens = RecordingPens()
        val drawn = renderer(pens, params, origin).draw(canvas, stroke(tool, points = points), scale, 5f, 7f)
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
    fun points_are_canvas_pixels_with_pressure_normalised_to_0_to_1() {
        val s = drawnBy(Tool.FOUNTAIN, scale = 2f)
        assertThat(s.points.map { it.x }).containsExactly(25f, 65f).inOrder()
        assertThat(s.points.map { it.y }).containsExactly(47f, 87f).inOrder()
        assertThat(s.points.map { it.pressure }).containsExactly(0.25f, 0.75f).inOrder()
        assertThat(s.points.map { it.timestamp }).containsExactly(0L, 16L).inOrder()
        assertThat(s.points.map { it.size }).containsExactly(s.width, s.width)
        assertThat(s.color).isEqualTo(PenColor.BLUE.argb)
    }

    @Test
    fun pressure_outside_0_to_1_is_clamped() {
        val s = drawnBy(Tool.BRUSH, points = listOf(Point(1f, 1f, 1.5f, 0), Point(2f, 2f, -0.5f, 16)))
        assertThat(s.points.map { it.pressure }).containsExactly(1f, 0f).inOrder()
    }

    @Test
    fun the_pen_demo_constants_are_0_3_pressure_sensitivity_and_0_6_smoothing() {
        assertThat(PRESSURE_SENSITIVITY).isEqualTo(0.3f)
        assertThat(SMOOTH_LEVEL).isEqualTo(0.6f)
    }

    @Test
    fun brush_config_has_type_width_colour_and_the_demo_pressure_and_smoothing() {
        val s = drawnBy(Tool.BRUSH, params = { floatArrayOf(0.9f, 0.9f) })
        val c = SdkOnyxPens.config(s)
        assertThat(c.type).isEqualTo(NeoPenConfig.NEOPEN_PEN_TYPE_BRUSH)
        assertThat(c.maxTouchPressure).isEqualTo(1f)
        assertThat(c.width).isEqualTo(s.width)
        assertThat(c.color).isEqualTo(s.color)
        assertThat(c.rotateAngle).isEqualTo(0)
        assertThat(c.pressureSensitivity).isEqualTo(0.3f)
        assertThat(c.smoothLevel).isEqualTo(0.6f)
    }

    @Test
    fun fountain_and_brush_ignore_the_firmware_array_and_do_not_read_it() {
        for (tool in listOf(Tool.FOUNTAIN, Tool.BRUSH)) {
            var asked = false
            val s =
                drawnBy(tool, params = {
                    asked = true
                    floatArrayOf(0.5f, 0.2f)
                })
            assertThat(asked).isFalse()
            assertThat(s.params).isEqualTo(PenParams())
        }
    }

    @Test
    fun charcoal_config_sets_only_what_the_vendor_wrapper_sets() {
        val defaults = NeoPenConfig()
        for (tool in listOf(Tool.CHARCOAL, Tool.CHARCOAL_V2)) {
            val s = drawnBy(tool)
            val c = SdkOnyxPens.config(s)
            assertThat(c.maxTouchPressure).isEqualTo(1f)
            assertThat(c.width).isEqualTo(s.width)
            assertThat(c.color).isEqualTo(s.color)
            assertThat(c.rotateAngle).isEqualTo(0)
            assertThat(c.type).isEqualTo(defaults.type)
            assertThat(c.pressureSensitivity).isEqualTo(defaults.pressureSensitivity)
            assertThat(c.smoothLevel).isEqualTo(defaults.smoothLevel)
            assertThat(c.tiltScale).isEqualTo(defaults.tiltScale)
        }
    }

    @Test
    fun the_fountain_width_cancels_the_3_px_that_create_neo_pen_v2_adds() {
        val table = mapOf(10f to 7f, 3f to 0f, 1f to 0f, 4.5f to 1.5f)
        for ((live, handed) in table) assertThat(fountainWidth(live)).isEqualTo(handed)
    }

    @Test
    fun charcoal_tilt_follows_the_device_switch_and_the_stroke_tilt() {
        val tilted = listOf(Point(10f, 20f, 0.25f, 0, tiltX = 30f), Point(30f, 40f, 0.75f, 16, tiltX = 28f))
        val on = SdkOnyxPens.config(drawnBy(Tool.CHARCOAL, params = { floatArrayOf(1f, 4f) }, points = tilted))
        assertThat(on.isTiltEnabled()).isTrue()
        val off = SdkOnyxPens.config(drawnBy(Tool.CHARCOAL_V2, params = { floatArrayOf(0f, 4f) }, points = tilted))
        assertThat(off.isTiltEnabled()).isFalse()
        for (raw in listOf(null, floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(-1f))) {
            val unknown = SdkOnyxPens.config(drawnBy(Tool.CHARCOAL, params = { raw }, points = tilted))
            assertThat(unknown.isTiltEnabled()).isTrue()
        }
        assertThat(SdkOnyxPens.config(drawnBy(Tool.CHARCOAL, params = { floatArrayOf(1f) })).isTiltEnabled()).isFalse()
    }

    @Test
    fun charcoal_screen_matrix_translates_canvas_pixels_to_the_screen_as_the_demo_does() {
        val s = drawnBy(Tool.CHARCOAL, origin = PointF(12f, 34f))
        assertThat(s.screenOrigin).isEqualTo(PointF(12f, 34f))
        val pt = floatArrayOf(1f, 2f).also { SdkOnyxPens.screenMatrix(s).mapPoints(it) }
        assertThat(pt.toList()).containsExactly(13f, 36f).inOrder()
        assertThat(SdkOnyxPens.screenMatrix(drawnBy(Tool.CHARCOAL)).isIdentity).isTrue()
    }

    @Test
    fun a_fallback_is_logged_with_its_exception_and_counted() {
        ShadowLog.clear()
        val before = InkDiag.fallbacks.value
        val error = UnsatisfiedLinkError("no neopen_jni")
        renderer(OnyxPens { _, _ -> throw error }).draw(canvas, stroke(Tool.FOUNTAIN), 1f, 0f, 0f)
        renderer(RecordingPens(draws = false)).draw(canvas, stroke(Tool.BRUSH), 1f, 0f, 0f)
        assertThat(InkDiag.fallbacks.value).isEqualTo(before + 2)
        val logs = ShadowLog.getLogsForTag(InkDiag.TAG)
        assertThat(logs).hasSize(2)
        assertThat(logs[0].throwable).isSameInstanceAs(error)
        assertThat(logs[0].msg).contains("FOUNTAIN")
        assertThat(logs[1].msg).contains("BRUSH")
        assertThat(logs[1].throwable).isNull()
    }

    @Test
    fun a_clinit_failure_is_logged_once_with_its_reason_and_exposed() {
        ShadowLog.clear()
        val cause = UninitializedPropertyAccessException("lateinit property appContext has not been initialized")
        val r = renderer(OnyxPens { _, _ -> throw ExceptionInInitializerError(cause) })
        repeat(3) { r.draw(canvas, stroke(Tool.FOUNTAIN), 1f, 0f, 0f) }
        val reason = "SDK pens off: kotlin.UninitializedPropertyAccessException: lateinit property appContext has not been initialized"
        assertThat(InkDiag.penOffReason.value).isEqualTo(reason)
        val offLogs = ShadowLog.getLogsForTag(InkDiag.TAG).filter { it.msg.contains("SDK pens off:") }
        assertThat(offLogs).hasSize(1)
        assertThat(offLogs[0].msg).contains(reason)
    }

    @Test
    fun a_plain_linkage_error_names_its_own_class_and_message() {
        renderer(OnyxPens { _, _ -> throw UnsatisfiedLinkError("no neopen_jni") }).draw(canvas, stroke(Tool.BRUSH), 1f, 0f, 0f)
        assertThat(InkDiag.penOffReason.value).isEqualTo("SDK pens off: java.lang.UnsatisfiedLinkError: no neopen_jni")
    }

    @Test
    fun strokes_are_still_counted_as_fallbacks_after_the_pens_broke() {
        val before = InkDiag.fallbacks.value
        val r = renderer(OnyxPens { _, _ -> throw UnsatisfiedLinkError("no neopen_jni") })
        assertThat(r.handles(stroke(Tool.FOUNTAIN))).isTrue()
        repeat(3) { r.draw(canvas, stroke(Tool.FOUNTAIN), 1f, 0f, 0f) }
        r.draw(canvas, stroke(Tool.BALLPOINT), 1f, 0f, 0f)
        assertThat(InkDiag.fallbacks.value).isEqualTo(before + 3)
    }

    @Test
    fun concurrent_fallbacks_are_all_counted() {
        val before = InkDiag.fallbacks.value
        val threads = List(8) { Thread { repeat(500) { InkDiag.fallback("x") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertThat(InkDiag.fallbacks.value).isEqualTo(before + 4000)
    }

    @Test
    fun a_stroke_the_pens_draw_is_no_fallback() {
        val before = InkDiag.fallbacks.value
        drawnBy(Tool.CHARCOAL)
        assertThat(InkDiag.fallbacks.value).isEqualTo(before)
    }

    @Test
    fun ballpoint_marker_and_shapes_are_left_to_the_app_renderer() {
        val pens = RecordingPens()
        val r = renderer(pens)
        for (tool in listOf(Tool.BALLPOINT, Tool.MARKER, Tool.RECTANGLE, Tool.ARROW)) {
            assertThat(r.draw(canvas, stroke(tool), 1f, 0f, 0f)).isFalse()
        }
        assertThat(pens.drawn).isEmpty()
    }

    @Test
    fun a_single_point_is_left_to_the_app_renderer() {
        val pens = RecordingPens()
        val dot = stroke(Tool.FOUNTAIN, points = listOf(Point(1f, 1f, 1f, 0)))
        assertThat(renderer(pens).draw(canvas, dot, 1f, 0f, 0f)).isFalse()
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
        val r = renderer(pens)
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
    fun the_pen_paint_is_opaque_in_the_stroke_colour() {
        val p = SdkOnyxPens.paint(Color.RED)
        assertThat(p.color).isEqualTo(Color.RED)
        assertThat(p.isAntiAlias).isTrue()
    }

    @Test
    fun the_fountain_paint_fills_its_closed_outline_as_the_demo_does() {
        val p = SdkOnyxPens.fillPaint(Color.RED)
        assertThat(p.style).isEqualTo(android.graphics.Paint.Style.FILL)
        assertThat(p.strokeWidth).isEqualTo(0f)
        assertThat(p.color).isEqualTo(Color.RED)
    }

    @Test
    fun tilt_of_the_stored_points_reaches_the_sdk_pens() {
        val tilted = listOf(Point(10f, 20f, 0.25f, 0, tiltX = 30f, tiltY = -12f), Point(30f, 40f, 0.75f, 16, tiltX = 28f))
        val pens = RecordingPens()
        renderer(pens).draw(canvas, stroke(Tool.CHARCOAL, points = tilted), 1f, 0f, 0f)
        val s = pens.drawn.single()
        assertThat(s.params.tiltEnabled).isTrue()
        assertThat(s.points.map { it.tiltX }).containsExactly(30, 28).inOrder()
        assertThat(s.points.map { it.tiltY }).containsExactly(-12, 0).inOrder()
        assertThat(SdkOnyxPens.config(s).isTiltEnabled()).isTrue()
    }

    @Test
    fun a_stroke_without_tilt_keeps_tilt_off() {
        val s = drawnBy(Tool.CHARCOAL)
        assertThat(s.params.tiltEnabled).isFalse()
        assertThat(s.points.map { it.tiltX }).containsExactly(0, 0)
        assertThat(SdkOnyxPens.config(s).isTiltEnabled()).isFalse()
    }

    @Test
    fun a_stroke_the_sdk_drew_nothing_for_falls_back_and_the_next_one_still_tries() {
        val pens = RecordingPens(draws = false)
        val r = renderer(pens)
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
