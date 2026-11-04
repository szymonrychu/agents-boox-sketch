package io.github.szymonrychu.booxsketch.documents

enum class SwipeDir { NEXT, PREV, NONE }

/** Pure finger swipe classifier: [dx], [dy] in px over [dtMs]. */
fun interface PageSwipe {
    fun classify(
        dx: Float,
        dy: Float,
        dtMs: Long,
    ): SwipeDir
}

/**
 * A right-to-left swipe is NEXT, left-to-right PREV. It needs [minTravelPx] of horizontal travel that dominates the
 * vertical one, and must not be a slow drag ([maxDurationMs]).
 */
class ThresholdPageSwipe(
    private val minTravelPx: Float = DEFAULT_MIN_TRAVEL_PX,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) : PageSwipe {
    override fun classify(
        dx: Float,
        dy: Float,
        dtMs: Long,
    ): SwipeDir =
        when {
            dtMs > maxDurationMs -> SwipeDir.NONE
            kotlin.math.abs(dx) < minTravelPx || kotlin.math.abs(dx) <= kotlin.math.abs(dy) -> SwipeDir.NONE
            dx < 0 -> SwipeDir.NEXT
            else -> SwipeDir.PREV
        }

    companion object {
        const val DEFAULT_MIN_TRAVEL_PX = 120f
        const val MAX_DURATION_MS = 1_500L
    }
}
