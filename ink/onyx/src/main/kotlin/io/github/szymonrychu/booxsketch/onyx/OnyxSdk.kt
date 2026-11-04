package io.github.szymonrychu.booxsketch.onyx

import android.content.Context
import android.util.Log
import com.onyx.android.sdk.base.utils.ResManager

/**
 * One-time Onyx SDK setup. The SDK pens read `ResManager.appContext` (a lateinit) in class
 * initialisers such as `PenUtils.<clinit>`; without `ResManager.init` the first SDK pen redraw
 * throws and every SDK pen is off for the rest of the process. The vendor OnyxAndroidDemo calls it
 * in `Application.onCreate`.
 */
object OnyxSdk {
    /** Calls `ResManager.init(context)` on an Onyx device with the pen SDK; a no-op elsewhere. */
    @Suppress("TooGenericExceptionCaught")
    fun init(
        context: Context,
        available: () -> Boolean = { OnyxProbe.available().available },
        resManagerInit: (Context) -> Unit = { ResManager.init(it) },
    ) {
        if (!available()) return
        try {
            resManagerInit(context)
        } catch (e: LinkageError) {
            warn(e)
        } catch (e: RuntimeException) {
            warn(e)
        }
    }

    private fun warn(e: Throwable) {
        try {
            Log.w(InkDiag.TAG, "ResManager.init failed", e)
        } catch (_: RuntimeException) {
            // android.util.Log is a stub in plain JVM tests.
        }
    }
}
