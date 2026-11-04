package io.github.szymonrychu.booxsketch.onyx

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.UiRefresh

/**
 * Gets Compose UI changes onto an e-ink panel that raw drawing freezes.
 *
 * Raw drawing stays held off from construction until the first frame is drawn; then a full
 * refresh runs and raw drawing is enabled for the first time. Each [uiChanged] holds raw drawing
 * off, waits for the next draw (Compose recomposes and draws), runs a partial refresh and
 * releases it. Unsettled changes (slider drags) run at most once per [minIntervalMs] with a
 * trailing run; a settled change runs at once. [chromeChanged] is a settled change that ends with
 * a full refresh instead of a partial one. Changes during a refresh run once after it.
 * All calls are on the main thread.
 */
class UiRefresher(
    private val input: StrokeInput,
    private val afterNextDraw: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val now: () -> Long,
    private val partialRefresh: () -> Unit,
    private val fullRefresh: () -> Unit,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) : UiRefresh {
    private var firstDrawn = false
    private var inFlight = false
    private var pending = false
    private var pendingFull = false
    private var closed = false
    private var lastRun = Long.MIN_VALUE / 2
    private var held = 0
    private val trailing = Runnable { run() }

    init {
        hold()
        afterNextDraw {
            if (!closed) {
                fullRefresh()
                firstDrawn = true
                release()
            }
        }
    }

    override fun uiChanged(settled: Boolean) {
        if (closed || !firstDrawn) return
        if (inFlight) {
            pending = true
            return
        }
        cancel(trailing)
        val wait = lastRun + minIntervalMs - now()
        if (settled || wait <= 0) run() else postDelayed(trailing, wait)
    }

    override fun chromeChanged() {
        if (closed || !firstDrawn) return
        if (inFlight) {
            pending = true
            pendingFull = true
            return
        }
        cancel(trailing)
        run(full = true)
    }

    private fun run(full: Boolean = false) {
        if (closed) return
        if (inFlight) {
            pending = true
            pendingFull = pendingFull || full
            return
        }
        inFlight = true
        lastRun = now()
        hold()
        afterNextDraw {
            if (!closed) {
                if (full) fullRefresh() else partialRefresh()
                inFlight = false
                release()
                if (pending) {
                    val nextFull = pendingFull
                    pending = false
                    pendingFull = false
                    run(nextFull)
                }
            }
        }
    }

    private fun hold() {
        held++
        input.holdUi()
    }

    private fun release() {
        held--
        input.releaseUi()
    }

    /** Releases every outstanding hold; later frames and changes are ignored. */
    fun close() {
        if (closed) return
        closed = true
        cancel(trailing)
        repeat(held) { input.releaseUi() }
        held = 0
    }

    companion object {
        const val MIN_INTERVAL_MS = 150L

        /** A refresher on [view]'s window, with the vendor EPD calls; [UiRefresh.None] for inputs that do not freeze the screen. */
        fun forView(
            view: View,
            input: StrokeInput,
        ): UiRefresh {
            if (!input.freezesUi) return UiRefresh.None
            val main = Handler(Looper.getMainLooper())
            return UiRefresher(
                input = input,
                afterNextDraw = { view.afterNextDraw(main, it) },
                postDelayed = { r, d -> main.postDelayed(r, d) },
                cancel = { main.removeCallbacks(it) },
                now = { android.os.SystemClock.uptimeMillis() },
                partialRefresh = { Epd.partialRefresh(view) },
                fullRefresh = { Epd.fullRefresh(view) },
            )
        }
    }
}

/**
 * Runs [action] on [main] after this view's window next draws: a pre-draw listener fires inside
 * the next traversal and posts [action], which therefore runs once that traversal (with its draw)
 * has finished. Forces the traversal with an invalidate.
 */
internal fun View.afterNextDraw(
    main: Handler,
    action: Runnable,
) {
    val first = viewTreeObserver
    first.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
            private var done = false

            override fun onPreDraw(): Boolean {
                if (done) return true
                done = true
                // The observer may have been merged into the window's since registering: remove
                // from both the captured and the current one.
                if (first.isAlive) first.removeOnPreDrawListener(this)
                val current = viewTreeObserver
                if (current.isAlive) current.removeOnPreDrawListener(this)
                main.post(action)
                return true
            }
        },
    )
    rootView.invalidate()
}
