package io.github.szymonrychu.booxsketch.ink

import android.view.MotionEvent

/** Where a touch event reached the input from. */
enum class DragSource {
    /** The wrapped Activity window callback, which sees events before any view (also in raw mode). */
    WINDOW,

    /** The board view's own touch listener. */
    VIEW,
}

/** A touch input that also takes events from the window callback, besides its view listener. */
interface WindowTouchFeed {
    /** Takes [ev], already in the pixels of the started view; true when the event belongs to a stroke. */
    fun feed(
        ev: MotionEvent,
        source: DragSource,
    ): Boolean

    /** Called for every taken event of a shape drag (repeated copies included), for Diagnostics. */
    var onShapeEvent: ((DragSource) -> Unit)?
}
