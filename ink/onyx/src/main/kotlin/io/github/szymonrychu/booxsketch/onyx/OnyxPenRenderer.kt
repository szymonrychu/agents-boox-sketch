package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenV2Wrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenWrapper
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoRenderPoint
import com.onyx.android.sdk.pen.PenRenderArgs
import com.onyx.android.sdk.pen.PenUtils
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlin.math.roundToInt

/** Draws a committed stroke with a vendor pen; false leaves it to the caller's own renderer. */
fun interface PenRenderer {
    /** False when [draw] would certainly return false for [stroke], so callers can skip preparing for it. */
    fun handles(stroke: Stroke): Boolean = true

    fun draw(
        canvas: Canvas,
        stroke: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Boolean
}

/** The Onyx pens whose look the app's renderer cannot reproduce (native pressure curve, texture). */
enum class SdkPen { FOUNTAIN, BRUSH, CHARCOAL, CHARCOAL_V2 }

/**
 * One stroke as the SDK pens take it: canvas pixels, raw pressure up to [maxPressure], the pen's
 * raw tilt per point and [tilt] when any point has one.
 */
data class SdkStroke(
    val pen: SdkPen,
    val points: List<TouchPoint>,
    val width: Float,
    val color: Int,
    val maxPressure: Float,
    val tilt: Boolean = false,
)

/** The SDK's offline pen renderers; a seam because they need the native libneopen_jni (device only). */
fun interface OnyxPens {
    /** False when the SDK drew nothing (it could not create the native pen). */
    fun draw(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean
}

/**
 * Redraws fountain, brush, charcoal and charcoal_v2 strokes with the Onyx SDK's own pens, the
 * ones behind the live raw ink, so the committed stroke looks like the live one: the native
 * pressure and velocity curve of fountain and brush, and the charcoal textures. The stroke is
 * handed over as the raw input drew it: canvas pixels, the raw brush width
 * ([BrushMapping.width]), raw pressure and raw tilt. Pressure is divided by the device maximum
 * exactly once, in the SDK's Java (the native pens never read `maxTouchPressure`; see MEMORY.md).
 * Without the native library (JVM tests, a non-Onyx device) the first failure turns it off for
 * good and every stroke falls back to the caller; a stroke the SDK drew nothing for (no native
 * pen) falls back on its own.
 */
class OnyxPenRenderer internal constructor(
    private val pens: OnyxPens,
    private val maxPressure: () -> Float,
) : PenRenderer {
    constructor() : this(SdkOnyxPens, Epd::maxTouchPressure)

    @Volatile
    private var broken = false

    override fun handles(stroke: Stroke) = !broken && pen(stroke.tool) != null && stroke.points.size >= 2

    @Suppress("TooGenericExceptionCaught")
    override fun draw(
        canvas: Canvas,
        stroke: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        if (broken) return false
        val pen = pen(stroke.tool) ?: return false
        if (stroke.points.size < 2) return false
        val max = maxPressure().let { if (it.isNaN() || it <= 0f) OnyxStrokeInput.FALLBACK_MAX_PRESSURE else it }
        val width = BrushMapping.width(stroke.tool, stroke.width * scale)
        val points = stroke.points.map { touchPoint(it, scale, dx, dy, max, width) }
        val tilt = stroke.points.any { it.tiltX != null || it.tiltY != null }
        return try {
            pens.draw(canvas, SdkStroke(pen, points, width, stroke.color.argb, max, tilt))
        } catch (_: LinkageError) {
            broken = true
            false
        } catch (_: RuntimeException) {
            broken = true
            false
        }
    }

    private fun touchPoint(
        p: Point,
        scale: Float,
        dx: Float,
        dy: Float,
        max: Float,
        width: Float,
    ) = TouchPoint(
        p.x * scale + dx,
        p.y * scale + dy,
        p.pressure * max,
        width,
        p.tiltX?.roundToInt() ?: 0,
        p.tiltY?.roundToInt() ?: 0,
        p.tMs,
    )

    private fun pen(tool: Tool): SdkPen? =
        when (tool) {
            Tool.FOUNTAIN -> SdkPen.FOUNTAIN
            Tool.BRUSH -> SdkPen.BRUSH
            Tool.CHARCOAL -> SdkPen.CHARCOAL
            Tool.CHARCOAL_V2 -> SdkPen.CHARCOAL_V2
            Tool.BALLPOINT, Tool.MARKER, Tool.RECTANGLE, Tool.ARROW -> null
        }

    companion object {
        /** The SDK pen renderer on an Onyx device with the pen SDK, else null (the app renderer only). */
        fun onDevice(): PenRenderer? = if (OnyxProbe.available().available) OnyxPenRenderer() else null
    }
}

/**
 * The SDK calls, as the vendor wrappers draw raw strokes (decompiled onyxsdk-pen 1.5.8), unrolled
 * so an empty result (no native pen) is seen: `NeoFountainPenWrapper.drawStroke` and
 * `NeoBrushPenWrapper.drawStroke` are computeStrokePoints plus `PenUtils.drawStrokeByPointSize`,
 * the charcoal `drawNormalStroke` is computeStrokeRenderPoints per 1000-point chunk plus one
 * drawBitmap per render point.
 */
internal object SdkOnyxPens : OnyxPens {
    private const val DISPLAY_SCALE = 1f
    private const val CHARCOAL_CHUNK = 1000

    override fun draw(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean =
        when (stroke.pen) {
            SdkPen.FOUNTAIN -> {
                drawSized(
                    canvas,
                    stroke,
                    NeoFountainPenWrapper.computeStrokePoints(stroke.points, DISPLAY_SCALE, stroke.width, stroke.maxPressure),
                )
            }

            SdkPen.BRUSH -> {
                drawSized(canvas, stroke, NeoBrushPenWrapper.computeStrokePoints(stroke.points, stroke.width, stroke.maxPressure))
            }

            SdkPen.CHARCOAL, SdkPen.CHARCOAL_V2 -> {
                drawCharcoal(canvas, stroke)
            }
        }

    private fun drawSized(
        canvas: Canvas,
        stroke: SdkStroke,
        sized: List<TouchPoint>?,
    ): Boolean {
        if (sized.isNullOrEmpty()) return false
        PenUtils.drawStrokeByPointSize(canvas, paint(stroke.color), sized, false)
        return true
    }

    private fun drawCharcoal(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean {
        var drawn = false
        for (chunk in stroke.points.chunked(CHARCOAL_CHUNK)) {
            val args = charcoalArgs(canvas, stroke.copy(points = chunk))
            val bitmaps = ArrayList<Bitmap>()
            val rendered =
                if (stroke.pen == SdkPen.CHARCOAL_V2) {
                    NeoCharcoalPenV2Wrapper.computeStrokeRenderPoints(args, bitmaps)
                } else {
                    NeoCharcoalPenWrapper.computeStrokeRenderPoints(args, bitmaps)
                }
            drawn = drawTextures(canvas, rendered, bitmaps) || drawn
        }
        return drawn
    }

    /** One texture bitmap per render point, as `drawNormalStrokeImpl` draws them; recycles them after. */
    fun drawTextures(
        canvas: Canvas,
        rendered: Array<NeoRenderPoint>?,
        bitmaps: List<Bitmap>,
    ): Boolean {
        try {
            if (rendered.isNullOrEmpty()) return false
            for (p in rendered) canvas.drawBitmap(bitmaps[p.bitmapIndex], p.x, p.y, null)
            return true
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }

    fun paint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }

    /** Points are already canvas pixels, so the screen matrix is the identity. */
    fun charcoalArgs(
        canvas: Canvas,
        stroke: SdkStroke,
    ): PenRenderArgs =
        PenRenderArgs()
            .setCanvas(canvas)
            .setPaint(paint(stroke.color))
            .setPenType(
                if (stroke.pen == SdkPen.CHARCOAL_V2) NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL_V2 else NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL,
            ).setColor(stroke.color)
            .setStrokeWidth(stroke.width)
            .setTiltEnabled(stroke.tilt)
            .setCreateArgs(ShapeCreateArgs().setMaxPressure(stroke.maxPressure))
            .setScreenMatrix(Matrix())
            .setErase(false)
            .setPoints(stroke.points)
}
