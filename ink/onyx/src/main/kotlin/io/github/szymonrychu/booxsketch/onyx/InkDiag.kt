package io.github.szymonrychu.booxsketch.onyx

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ink diagnostics read out from the device with `adb logcat -s InkDiag`: every SDK pen redraw that
 * fell back to the app renderer ([fallback], counted in [fallbacks] for the Diagnostics panel) and
 * the geometry of every committed stroke ([info]).
 */
object InkDiag {
    const val TAG = "InkDiag"

    private val count = MutableStateFlow(0)

    /** SDK pen redraws that fell back to the app renderer since the process started. */
    val fallbacks: StateFlow<Int> = count.asStateFlow()

    /** Logs and counts one fallback; [error] is the exception that caused it, if any. */
    fun fallback(
        message: String,
        error: Throwable? = null,
    ) {
        count.value += 1
        write { Log.w(TAG, "fallback #${count.value}: $message", error) }
    }

    fun info(message: String) {
        write { Log.i(TAG, message) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun write(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // android.util.Log is a stub in plain JVM tests; diagnostics never break drawing.
        }
    }
}
