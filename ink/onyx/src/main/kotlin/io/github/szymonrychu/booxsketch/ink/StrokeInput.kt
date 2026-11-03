package io.github.szymonrychu.booxsketch.ink

import android.graphics.Rect
import android.view.View
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.Flow

/** Events produced by a pen input; coordinates are in the pixels of the view passed to [StrokeInput.start]. */
sealed interface StrokeEvent {
    data class Committed(
        val stroke: Stroke,
    ) : StrokeEvent

    data class Erase(
        val x: Float,
        val y: Float,
        val radius: Float,
    ) : StrokeEvent
}

/** Source of pen strokes: the Onyx raw-drawing SDK on Boox devices, plain touch events elsewhere. */
interface StrokeInput {
    /** Starts listening on [view]; input outside [limit] or inside any of [exclude] is ignored. */
    fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    )

    fun setTool(
        tool: Tool,
        width: Float,
    )

    fun pause()

    fun resume()

    fun stop()

    val events: Flow<StrokeEvent>
}
