package io.github.szymonrychu.booxsketch.ink

import android.view.MotionEvent
import io.github.szymonrychu.booxsketch.documents.PageSwipe
import io.github.szymonrychu.booxsketch.documents.SwipeDir

/**
 * Turns finger touch events (only; the pen never counts) into page swipes: the pen draws, the finger pages. Fed from
 * the window callback, which sees events before any view and also while Onyx raw drawing owns the pen.
 */
class FingerSwipeTracker(
    private val swipe: PageSwipe,
    private val sink: () -> ((SwipeDir) -> Unit)?,
) {
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false

    fun feed(ev: MotionEvent) {
        if (sink() == null) {
            tracking = false
            return
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false
            }

            MotionEvent.ACTION_CANCEL -> {
                tracking = false
            }

            MotionEvent.ACTION_UP -> {
                finish(ev)
            }
        }
    }

    private fun finish(ev: MotionEvent) {
        if (!tracking) return
        tracking = false
        val dir = swipe.classify(ev.x - downX, ev.y - downY, ev.eventTime - downTime)
        if (dir != SwipeDir.NONE) sink()?.invoke(dir)
    }
}
