package io.github.szymonrychu.booxsketch.onyx

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.device.Device

/** E-ink helpers; every call is a no-op (or 0) when the vendor controller is unavailable. */
object Epd {
    /** Fast pen-friendly refresh while drawing. */
    fun handwritingMode(view: View) {
        safely { EpdController.setViewDefaultUpdateMode(view, UpdateMode.HAND_WRITING_REPAINT_MODE) }
    }

    /**
     * Back to the view's normal update mode after [handwritingMode], as the vendor pen demo does
     * after its partial refresh: the handwriting mode is near-binary (no grey, no anti-aliasing,
     * no texture), so every frame drawn in it looks thinner than the live ink.
     */
    fun resetUpdateMode(view: View) {
        safely { EpdController.resetViewUpdateMode(view) }
    }

    /**
     * Full-quality GC refresh of the whole screen that clears ghosting, as in the vendor EPD demo
     * (`repaintEveryThing(GC)`). While raw drawing is on the screen is frozen, so callers run this
     * inside `StrokeInput.refresh`. [view] is the caller's view; the GC repaint covers the whole
     * screen, so it is not needed for the vendor call.
     */
    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun fullRefresh(view: View) {
        safely { EpdController.repaintEveryThing(UpdateMode.GC) }
    }

    /**
     * Non-flashing GU refresh of [view]'s window, for toolbar changes made while raw drawing is
     * held off (see [UiRefresher]).
     */
    fun partialRefresh(view: View) {
        safely { EpdController.invalidate(view.rootView, UpdateMode.GU) }
    }

    /** The device's maximum raw pen pressure, or 0 when unknown. */
    fun maxTouchPressure(): Float {
        var max = 0f
        safely { max = EpdController.getMaxTouchPressure() }
        return max
    }

    /**
     * The firmware's parameters of stroke [style] (`Device.getStrokeParameters`), or null when
     * unknown. Fountain and brush hold [pressure sensitivity, smooth level], the charcoals
     * [tilt enabled, tilt scale] (vendor pen demo, `PenInfoUtils` and `CharcoalScribbleShape`).
     */
    fun strokeParameters(style: Int): FloatArray? {
        var params: FloatArray? = null
        safely { params = Device.currentDevice().getStrokeParameters(style) }
        return params?.takeIf { it.isNotEmpty() }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // vendor controller refused or missing; refresh is cosmetic
        } catch (_: LinkageError) {
            // not an Onyx device
        }
    }
}

/**
 * Collapses bursts of full-refresh requests (page switches, clears) into one [action] run
 * [delayMs] after the last request. [post] and [cancel] schedule on the UI thread, for example
 * `View.postDelayed` and `View.removeCallbacks`.
 */
class FullRefreshDebouncer(
    private val delayMs: Long,
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val action: () -> Unit,
) {
    private val run = Runnable { action() }

    fun request() {
        cancel(run)
        post(run, delayMs)
    }

    fun cancel() = cancel(run)
}
