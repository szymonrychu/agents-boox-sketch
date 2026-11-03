package io.github.szymonrychu.booxsketch.onyx

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/** E-ink refresh helpers; every call is a no-op when the vendor controller is unavailable. */
object Epd {
    /** Fast pen-friendly refresh while drawing. */
    fun handwritingMode(view: View) {
        safely { EpdController.setViewDefaultUpdateMode(view, UpdateMode.HAND_WRITING_REPAINT_MODE) }
    }

    /** Full-quality refresh that clears ghosting. */
    fun fullRefresh(view: View) {
        safely { EpdController.refreshScreen(view, UpdateMode.GC) }
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
