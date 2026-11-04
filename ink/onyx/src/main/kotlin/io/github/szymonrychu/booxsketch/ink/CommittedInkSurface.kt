package io.github.szymonrychu.booxsketch.ink

/**
 * A host view that leaves committed pen ink to the raw drawing overlay while [StrokeInput.rawLive]
 * is true (an app frame posted then stalls the next stroke's ink) and catches up later. A raw pen
 * input calls [syncCommitted] on the main thread when the pen goes idle and right before raw
 * drawing turns off or closes, so the surface holds every committed stroke before the overlay goes.
 */
interface CommittedInkSurface {
    /** Draws the committed strokes not yet on the surface (dirty rect only); does nothing when none are pending. */
    fun syncCommitted()
}
