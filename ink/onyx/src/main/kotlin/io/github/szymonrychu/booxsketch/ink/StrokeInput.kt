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

    /**
     * Runs [redraw], an app-side redraw of the board, so that it reaches the screen. Raw pen
     * inputs freeze the screen while raw drawing is on, so they bracket [redraw] with a disable
     * and enable; plain inputs just call it.
     */
    fun refresh(redraw: () -> Unit) = redraw()

    /** True when this input freezes the e-ink refresh of the rest of the UI while active (Onyx raw drawing). */
    val freezesUi: Boolean get() = false

    /**
     * Lifts the screen freeze (raw drawing off) until a matching [releaseUi]; calls nest. Plain
     * inputs ignore both. Used to let Compose UI changes and the first frame reach the panel.
     */
    fun holdUi() = Unit

    fun releaseUi() = Unit

    val events: Flow<StrokeEvent>
}
