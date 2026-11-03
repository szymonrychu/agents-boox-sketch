package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect

/**
 * The part of a board view that stylus input may use: the view bounds minus the system bar and
 * display cutout areas of the window. The result is in view-local pixels, as the stroke input
 * expects, and never negative.
 *
 * @param viewInWindow the view bounds in window coordinates
 * @param window the window bounds
 * @param insets the bar and cutout sizes as left, top, right, bottom edge widths
 */
object BoardLimit {
    fun compute(
        viewInWindow: Rect,
        window: Rect,
        insets: Rect,
    ): Rect {
        val left = maxOf(0, insets.left - viewInWindow.left)
        val top = maxOf(0, insets.top - viewInWindow.top)
        val right = minOf(viewInWindow.width(), window.right - insets.right - viewInWindow.left)
        val bottom = minOf(viewInWindow.height(), window.bottom - insets.bottom - viewInWindow.top)
        return Rect(left, top, maxOf(left, right), maxOf(top, bottom))
    }
}
