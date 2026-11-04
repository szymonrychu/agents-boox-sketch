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

    fun width(width: Float): Float = width.coerceAtLeast(MIN_WIDTH)
}
