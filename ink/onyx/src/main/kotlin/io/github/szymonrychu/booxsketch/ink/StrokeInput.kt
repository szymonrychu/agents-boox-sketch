package io.github.szymonrychu.booxsketch.ink

import android.graphics.Rect
import android.view.View
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * The stroke published on [StrokeInput.inProgress] was dropped without a commit (cancel, pause,
     * stop). Sent on [StrokeInput.events], after any earlier commit, so a consumer can tell a lift
     * (commit, then clear) from a drop with one ordered stream.
     */
    data object Cancelled : StrokeEvent
}

/** Source of pen strokes: the Onyx raw-drawing SDK on Boox devices, plain touch events elsewhere. */
interface StrokeInput {
    /** Starts listening on [view]; input outside [limit] or inside any of [exclude] is ignored. */
    fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    )

    /**
     * Pen tool and width. A shape tool ([Tool.isShape]) is drawn by dragging: the input then
     * publishes the drag on [inProgress] (tool set to the shape) and commits it on lift, and a raw
     * pen input must not draw its own freehand ink meanwhile.
     */
    fun setTool(
        tool: Tool,
        width: Float,
    )

    /** Pen colour of the ink drawn live and of the strokes committed from now on. */
    fun setColor(color: PenColor) = Unit

    fun pause()

    fun resume()

    fun stop()

    /**
     * Runs [redraw], an app-side redraw of the board, so that it reaches the screen. Raw pen
     * inputs freeze the screen while raw drawing is on, so they bracket [redraw] with a disable
     * and enable; plain inputs just call it.
     */
    fun refresh(redraw: () -> Unit) = redraw()

    /**
     * True while a raw pen input's firmware overlay draws the ink (raw drawing open and enabled):
     * a committed stroke must then not touch the host surface; see [CommittedInkSurface].
     */
    val rawLive: Boolean get() = false

    /** True when this input freezes the e-ink refresh of the rest of the UI while active (Onyx raw drawing). */
    val freezesUi: Boolean get() = false

    /**
     * Lifts the screen freeze (raw drawing off) until a matching [releaseUi]; calls nest. Plain
     * inputs ignore both. Used to let Compose UI changes and the first frame reach the panel.
     */
    fun holdUi() = Unit

    fun releaseUi() = Unit

    /** True from pen-down to pen-up of a stroke or erase. Restarting the input while true would drop that stroke. */
    val strokeInProgress: Boolean get() = false

    val events: Flow<StrokeEvent>

    /**
     * The stroke being drawn, in view pixels, for an input whose ink the app must draw itself
     * while the pen moves (touch, and shape drags on any input); null between strokes. Raw pen
     * inputs render their own freehand ink and keep it null. On lift the stroke is committed on
     * [events] before this goes back to null; a stroke dropped without a commit sends
     * [StrokeEvent.Cancelled] instead.
     */
    val inProgress: StateFlow<Stroke?> get() = NO_STROKE

    private companion object {
        val NO_STROKE: StateFlow<Stroke?> = MutableStateFlow<Stroke?>(null).asStateFlow()
    }
}
