package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import io.github.szymonrychu.booxsketch.ink.StrokeInput

/** What an overlay over the board last reported: its state and its exclude rects (window px). */
internal class OverlayReport(
    val visible: Boolean,
    val rects: List<Rect>,
)

/**
 * The one hold and release path of every overlay drawn over the board (dock, side panel, popups).
 * A [change] applies a state flip and, for the first outstanding change only, holds raw drawing
 * off ([StrokeInput.holdUi]); each overlay then [report]s its state and rects. Once every awaited
 * key reported the requested state (with non-empty rects where asked), [settled] turns true and
 * [finishIfSettled] releases the hold exactly once. Several overlays changed by one tap share one
 * hold and one release. State is snapshot state, so composition observes [rects] and [settled].
 */
@Stable
internal class ChromeHolds(
    private val input: StrokeInput,
) {
    private class Pending(
        val target: Boolean,
        val needsRects: Boolean,
    )

    private val awaiting = mutableStateMapOf<String, Pending>()
    private val reports = mutableStateMapOf<String, OverlayReport>()

    // First-report order of the keys, so the exclude list order is stable across recompositions.
    private val order = mutableStateListOf<String>()

    /** Union of the last reported rects of every overlay. */
    val rects: List<Rect>
        get() = order.flatMap { reports[it]?.rects.orEmpty() }

    /** True when something awaits and every awaited overlay reported its requested state. */
    val settled: Boolean
        get() =
            awaiting.isNotEmpty() &&
                awaiting.all { (key, p) ->
                    val r = reports[key]
                    r != null && overlaySettled(p.target, p.needsRects, r.visible, r.rects)
                }

    /** True when overlay [key] last reported [visible] with non-empty rects. */
    fun reported(
        key: String,
        visible: Boolean,
    ): Boolean = reports[key]?.let { overlaySettled(visible, needsRects = true, visible = it.visible, rects = it.rects) } ?: false

    /**
     * Drops the rects of the stored reports of [keys] (their state stays): a layout change makes them
     * stale, so only a report made after this call counts as reported again.
     */
    fun invalidateRects(vararg keys: String) {
        for (key in keys) reports[key]?.let { reports[key] = OverlayReport(it.visible, emptyList()) }
    }

    /** Runs [apply] (the state flip of overlay [key] to [target]), holding raw drawing if nothing was pending. */
    fun change(
        key: String,
        target: Boolean,
        needsRects: Boolean = true,
        apply: () -> Unit,
    ) {
        apply()
        if (awaiting.isEmpty()) input.holdUi()
        awaiting[key] = Pending(target, needsRects)
    }

    fun report(
        key: String,
        visible: Boolean,
        rects: List<Rect>,
    ) {
        if (key !in reports) order += key
        reports[key] = OverlayReport(visible, rects)
    }

    /** Ends the burst: releases the hold once when [settled]; a no-op otherwise. */
    fun finishIfSettled() {
        if (!settled) return
        awaiting.clear()
        input.releaseUi()
    }

    /** On dispose: releases a hold still outstanding, once. */
    fun release() {
        if (awaiting.isEmpty()) return
        awaiting.clear()
        input.releaseUi()
    }
}

/**
 * True when an overlay reported [visible] == [target], with non-empty [rects] if [needsRects].
 * Empty rects of a state that needs them never count: right after a show or hide the new rect is
 * not measured yet, and releasing the hold then would run raw drawing with no exclude there.
 */
internal fun overlaySettled(
    target: Boolean,
    needsRects: Boolean,
    visible: Boolean,
    rects: List<Rect>,
): Boolean = visible == target && (!needsRects || rects.isNotEmpty())
