package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPen
import com.onyx.android.sdk.pen.NeoCharcoalPen
import com.onyx.android.sdk.pen.NeoCharcoalPenV2
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.NeoPen
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.NeoPenUtils
import com.onyx.android.sdk.pen.NeoRenderPoint
import com.onyx.android.sdk.pen.PenResult
import com.onyx.android.sdk.pen.PenUtils
import com.onyx.android.sdk.pen.utils.FountainShapes
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlin.math.roundToInt
import com.onyx.android.sdk.pen.utils.PenUtils as PenDefaults

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
 * The pen settings a stroke is redrawn with. Fountain and brush take the vendor demo's constants
 * ([FountainShapes.createNeoPenV2] and `PenInfoUtils`: [PRESSURE_SENSITIVITY], [SMOOTH_LEVEL]),
 * not the firmware's array, whose layout is not verified. Only the charcoals vary: tilt.
 */
data class PenParams(
    val tiltEnabled: Boolean = false,
)

/** The demo's pressure sensitivity (`PenInfoUtils.getDefaultPressureSensitivity`): 0.3, as `NeoPenConfig` and `createNeoPenV2`. */
internal const val PRESSURE_SENSITIVITY = PenDefaults.KEPLER_DEFAULT_PRESSURE_SENSITIVITY

/** The demo's smooth level (`PenInfoUtils.getDefaultSmoothLevel`). */
internal const val SMOOTH_LEVEL = PenDefaults.DEFAULT_SMOOTH_LEVEL

/**
 * The [PenParams] of [pen]. Only the charcoals read the firmware's stroke parameters [raw], as the
 * demo's `CharcoalScribbleShape.loadTiltConfig`: [tilt enabled, ...]. Tilt is on only when the
 * firmware allows it (or does not say) and the stroke kept tilt ([pointTilt]); strokes.json drops it.
 */
fun penParams(
    pen: SdkPen,
    raw: FloatArray?,
    pointTilt: Boolean,
): PenParams =
    when (pen) {
        SdkPen.FOUNTAIN, SdkPen.BRUSH -> {
            PenParams()
        }

        SdkPen.CHARCOAL, SdkPen.CHARCOAL_V2 -> {
            val allowed = raw?.getOrNull(0)?.takeIf { !it.isNaN() && it >= 0f }?.let { it != 0f } ?: true
            PenParams(tiltEnabled = pointTilt && allowed)
        }
    }

/**
 * One stroke as the SDK pens take it: canvas pixels, pressure normalised to 0..1 (the pens run
 * with `maxTouchPressure` 1), the pen's raw tilt per point, the pen [params], and the
 * canvas origin on the screen ([screenOrigin]) for the charcoal screen matrix.
 */
data class SdkStroke(
    val pen: SdkPen,
    val points: List<TouchPoint>,
    val width: Float,
    val color: Int,
    val params: PenParams = PenParams(),
    val screenOrigin: PointF = PointF(),
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
 * ([BrushMapping.width]), pressure in 0..1 and raw tilt, with the demo's pen settings ([penParams];
 * the charcoals' tilt switch comes from the firmware's [strokeParameters]). Without the native library (JVM
 * tests, a non-Onyx device) the first failure turns it off for good and every stroke falls back to
 * the caller; a stroke the SDK drew nothing for (no native pen) falls back on its own. Every
 * fallback is logged and counted ([InkDiag]).
 */
class OnyxPenRenderer internal constructor(
    private val pens: OnyxPens,
    private val strokeParameters: (Int) -> FloatArray?,
    private val screenOrigin: () -> PointF,
) : PenRenderer {
    constructor(screenOrigin: () -> PointF = ::PointF) : this(SdkOnyxPens, Epd::strokeParameters, screenOrigin)

    @Volatile
    private var broken = false

    /** Not `!broken`: a stroke the SDK pens would have drawn still reaches [draw] once they are off, so it is counted. */
    override fun handles(stroke: Stroke) = pen(stroke.tool) != null && stroke.points.size >= 2

    @Suppress("TooGenericExceptionCaught")
    override fun draw(
        canvas: Canvas,
        stroke: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        val pen = pen(stroke.tool) ?: return false
        if (stroke.points.size < 2) return false
        if (broken) {
            InkDiag.fallback("${stroke.tool} stroke ${stroke.id}: the SDK pens are off")
            return false
        }
        val width = BrushMapping.width(stroke.tool, stroke.width * scale)
        val points = stroke.points.map { touchPoint(it, scale, dx, dy, width) }
        val tilt = stroke.points.any { it.tiltX != null || it.tiltY != null }
        val raw = if (pen == SdkPen.CHARCOAL || pen == SdkPen.CHARCOAL_V2) strokeParameters(BrushMapping.style(stroke.tool)) else null
        val params = penParams(pen, raw, tilt)
        return try {
            pens.draw(canvas, SdkStroke(pen, points, width, stroke.color.argb, params, screenOrigin())).also {
                if (!it) InkDiag.fallback("${stroke.tool} stroke ${stroke.id}: the SDK pen drew nothing")
            }
        } catch (e: LinkageError) {
            off(stroke, e)
        } catch (e: RuntimeException) {
            off(stroke, e)
        }
    }

    private fun off(
        stroke: Stroke,
        e: Throwable,
    ): Boolean {
        broken = true
        InkDiag.fallback("${stroke.tool} stroke ${stroke.id}: ${InkDiag.penOff(e)}", e)
        return false
    }

    private fun touchPoint(
        p: Point,
        scale: Float,
        dx: Float,
        dy: Float,
        width: Float,
    ) = TouchPoint(
        p.x * scale + dx,
        p.y * scale + dy,
        p.pressure.coerceIn(0f, 1f),
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
        /**
         * The SDK pen renderer on an Onyx device with the pen SDK, else null (the app renderer
         * only). [screenOrigin] is where the drawn canvas's (0, 0) sits on the screen.
         */
        fun onDevice(screenOrigin: () -> PointF = ::PointF): PenRenderer? =
            if (OnyxProbe.available().available) OnyxPenRenderer(screenOrigin) else null
    }
}

/**
 * The width to hand `createNeoPenV2`: the [BrushMapping] width unchanged. The SDK adds
 * `FOUNTAIN_PEN_V1_COMPENSATION / scale` (3 px at scale 1) to it on purpose, and the live ink has
 * the same look; subtracting those 3 px (PR #51) made the redraw thinner than the live ink on the
 * device (v0.12.1: "fountain gets thiner after redraw").
 */
internal fun fountainWidth(width: Float): Float = width

/** The SDK's readers take a non-null pair type but check each half for null themselves. */
@Suppress("UNCHECKED_CAST")
private fun nonNull(p: Pair<PenResult?, PenResult?>) = p as Pair<PenResult, PenResult>

/**
 * The SDK pens driven as the vendor pen demo (OnyxAndroidDemo, OnyxPenDemo) draws its shapes:
 * fountain as `BrushScribbleShape` (`FountainShapes.createNeoPenV2` plus `NeoPenRender`, FILL
 * paint), brush and the charcoals through the native pen's create, onPenDown, onPenMove and
 * onPenUp, then the result read back (`NeoPenUtils.readPointResult`/`readTextureResult`, as
 * `NeoPenUtils.computeStrokePoints` and `NeoCharcoalPenWrapper.computeStrokeRenderPoints` do),
 * but with the full [config] instead of the wrappers' bare `NeoPenConfig`. The charcoals map the
 * points with the demo's screen matrix (`RendererUtils.getPointMatrix`, a translation to the view's
 * screen position) and back. An empty result (no native pen) returns false.
 */
internal object SdkOnyxPens : OnyxPens {
    private const val CHARCOAL_CHUNK = 1000
    private const val DISPLAY_SCALE = 1f

    override fun draw(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean =
        when (stroke.pen) {
            SdkPen.FOUNTAIN -> drawFountain(canvas, stroke)
            SdkPen.BRUSH -> drawBrush(canvas, stroke)
            SdkPen.CHARCOAL, SdkPen.CHARCOAL_V2 -> drawCharcoal(canvas, stroke)
        }

    /**
     * The pen config of [stroke], pressure already in 0..1. The charcoals get only what the
     * vendor `NeoCharcoalPenWrapper` sets (colour, width, tilt, rotate angle, max pressure) and
     * keep the SDK defaults for the rest; the brush adds the demo's pressure and smoothing.
     */
    fun config(stroke: SdkStroke): NeoPenConfig =
        NeoPenConfig().apply {
            setWidth(stroke.width)
            setColor(stroke.color)
            setMaxTouchPressure(1f)
            setTiltEnabled(stroke.params.tiltEnabled)
            setRotateAngle(0)
            if (stroke.pen == SdkPen.BRUSH) {
                setType(NeoPenConfig.NEOPEN_PEN_TYPE_BRUSH)
                pressureSensitivity = PRESSURE_SENSITIVITY
                smoothLevel = SMOOTH_LEVEL
            }
        }

    /** The demo's charcoal screen matrix: canvas pixels translated to the screen. */
    fun screenMatrix(stroke: SdkStroke): Matrix = Matrix().apply { postTranslate(stroke.screenOrigin.x, stroke.screenOrigin.y) }

    private fun drawFountain(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean {
        val pen =
            FountainShapes.createNeoPenV2(
                fountainWidth(stroke.width),
                NeoFountainPenWrapper.MIN_FOUNTAIN_PEN_WIDTH,
                DISPLAY_SCALE,
                DISPLAY_SCALE,
                DISPLAY_SCALE,
                DISPLAY_SCALE,
                PRESSURE_SENSITIVITY,
                true,
                SMOOTH_LEVEL,
            ) ?: return false
        val render = NeoPenRender(pen)
        try {
            render.onTouchPointList(stroke.points)
            if (render.penResults.isEmpty()) return false
            render.render(canvas, fillPaint(stroke.color))
            return true
        } finally {
            render.destroyPen()
        }
    }

    private fun drawBrush(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean {
        val sized = ArrayList<TouchPoint>()
        run(NeoBrushPen.create(config(stroke)), stroke.points) { NeoPenUtils.readPointResult(nonNull(it), sized) }
        if (sized.isEmpty()) return false
        PenUtils.drawStrokeByPointSize(canvas, paint(stroke.color), sized, false)
        return true
    }

    private fun drawCharcoal(
        canvas: Canvas,
        stroke: SdkStroke,
    ): Boolean {
        val matrix = screenMatrix(stroke)
        val inverse = Matrix().also { matrix.invert(it) }
        var drawn = false
        for (chunk in stroke.points.chunked(CHARCOAL_CHUNK)) {
            val config = config(stroke)
            val pen = if (stroke.pen == SdkPen.CHARCOAL_V2) NeoCharcoalPenV2.create(config) else NeoCharcoalPen.create(config)
            val bitmaps = ArrayList<Bitmap>()
            val rendered = ArrayList<NeoRenderPoint>()
            run(pen, NeoPenUtils.mapToPenCanvas(chunk, matrix)) { NeoPenUtils.readTextureResult(nonNull(it), bitmaps, rendered) }
            val back = if (rendered.isEmpty()) null else NeoPenUtils.mapFromPenCanvas(rendered.toTypedArray(), bitmaps, inverse)
            drawn = drawTextures(canvas, back, bitmaps) || drawn
        }
        return drawn
    }

    /** One pen stroke: down on the first point, move over the middle ones, up on the last; then the pen is freed. */
    private fun run(
        pen: NeoPen?,
        points: List<TouchPoint>,
        read: (Pair<PenResult?, PenResult?>) -> Unit,
    ) {
        if (pen == null || points.size < 2) return
        try {
            read(pen.onPenDown(points.first(), true))
            if (points.size > 2) read(pen.onPenMove(points.subList(1, points.size - 1), null, true))
            read(pen.onPenUp(points.last(), true))
        } finally {
            pen.destroy()
        }
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

    /** The fountain V2 pen outputs closed outlines: FILL, as the demo's `applyFountainPaintStyle`. */
    fun fillPaint(color: Int): Paint =
        paint(color).apply {
            style = Paint.Style.FILL
            strokeWidth = 0f
        }
}
