package io.github.szymonrychu.booxsketch.onyx

import com.onyx.android.sdk.pen.TouchHelper
import io.github.szymonrychu.booxsketch.model.Tool

/** Maps the app's tools to Onyx stroke styles. */
object BrushMapping {
    private const val MIN_WIDTH = 1f

    fun style(tool: Tool): Int =
        when (tool) {
            Tool.FOUNTAIN -> TouchHelper.STROKE_STYLE_FOUNTAIN
            Tool.BRUSH -> TouchHelper.STROKE_STYLE_NEO_BRUSH
            Tool.BALLPOINT, Tool.RECTANGLE, Tool.ARROW -> TouchHelper.STROKE_STYLE_PENCIL
            Tool.CHARCOAL -> TouchHelper.STROKE_STYLE_CHARCOAL
            Tool.CHARCOAL_V2 -> TouchHelper.STROKE_STYLE_CHARCOAL_V2
            Tool.MARKER -> TouchHelper.STROKE_STYLE_MARKER
        }

    /**
     * Raw brush width in view pixels (the SDK passes it straight to the renderer, no density).
     * It is the width the app's renderer redraws the committed stroke with at full pressure: the
     * selected width (view pixels; a commit divides it by the view scale and the redraw multiplies
     * it back) times the tool's factor, so the live and the redrawn stroke match.
     */
    fun width(
        tool: Tool,
        width: Float,
    ): Float = (width * widthFactor(tool)).coerceAtLeast(MIN_WIDTH)

    /** Keep in step with the width table of `CanvasStrokeRenderer.style`. */
    private fun widthFactor(tool: Tool): Float =
        when (tool) {
            Tool.CHARCOAL_V2 -> CHARCOAL_V2_FACTOR
            Tool.MARKER -> MARKER_FACTOR
            else -> 1f
        }

    private const val CHARCOAL_V2_FACTOR = 1.2f
    private const val MARKER_FACTOR = 3f
}
