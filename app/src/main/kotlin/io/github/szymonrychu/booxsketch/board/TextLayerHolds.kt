package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.onyx.OnyxStrokeInput
import kotlinx.coroutines.delay

/** The board surface a new text layer is swapped onto, inside a hold (raw drawing off). */
interface TextLayerSurface {
    /**
     * Shows [strokes] and [textLayer] now: committed ink is synced first, then [dirtyPage] (page px; null for the whole
     * page) is drawn and refreshed in GU. The caller holds raw drawing off.
     */
    fun swapText(
        strokes: List<Stroke>,
        textLayer: Bitmap?,
        dirtyPage: RectF?,
    )
}

/**
 * When and how a new text layer reaches the board (spec 6, Review Focus 3). Raw drawing freezes the screen, so the
 * swap never happens while it is live: [awaitPenIdle] waits until no stroke is in progress and the pen was quiet for
 * [PEN_IDLE_MS] (the SDK's pen-up refresh time, so a pause between letters does not count), then [hold] brackets the
 * swap with exactly one [StrokeInput.holdUi] (sync committed ink, raw off) and its release (raw on), the same hold
 * every overlay change uses. The board composable fills in [input] and [surface] while it is shown and reports pen
 * activity; without a board the swap is only a model change.
 */
class TextLayerHolds(
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    @Volatile var input: StrokeInput? = null

    @Volatile var surface: TextLayerSurface? = null

    /** Asked, inside a hold after a swap, to get the swap onto the frozen panel (the refresh waits for a draw). */
    @Volatile var ui: UiRefresh? = null

    @Volatile private var lastActivity: Long? = null

    /** A stroke, erase or drag event of the board's input. */
    fun onPenActivity() {
        lastActivity = clock()
    }

    /** True when no stroke is in progress and the last pen activity is [PEN_IDLE_MS] old. */
    fun idleNow(): Boolean = input?.strokeInProgress != true && quietFor() >= PEN_IDLE_MS

    private fun quietFor(): Long = lastActivity?.let { clock() - it } ?: Long.MAX_VALUE

    suspend fun awaitPenIdle() {
        while (!idleNow()) {
            val left = PEN_IDLE_MS - quietFor()
            delay(if (input?.strokeInProgress == true || left <= 0) POLL_MS else left)
        }
    }

    /** Runs [block] under one hold of the board's input (none without a board). */
    fun <T> hold(block: () -> T): T {
        val held = input
        held?.holdUi()
        try {
            return block()
        } finally {
            held?.releaseUi()
        }
    }

    companion object {
        const val PEN_IDLE_MS = OnyxStrokeInput.PEN_IDLE_MS.toLong()
        private const val POLL_MS = 50L
    }
}
