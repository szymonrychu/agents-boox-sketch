package io.github.szymonrychu.booxsketch.ink

/**
 * A [StrokeInput] whose pause and resume are counted per holder: the input really pauses on the
 * first pause and resumes only when the last holder resumes. The activity lifecycle gate (system
 * dialogs, focus loss) and the send outcome dialog both pause the same input; with a plain
 * boolean one of them would un-pause the input under the other.
 */
class CountedPause(
    private val delegate: StrokeInput,
) : StrokeInput by delegate {
    private var holds = 0

    @Synchronized
    override fun pause() {
        if (holds++ == 0) delegate.pause()
    }

    @Synchronized
    override fun resume() {
        if (holds == 0) return
        if (--holds == 0) delegate.resume()
    }
}
