package io.github.szymonrychu.booxsketch.onyx

import android.os.Build

data class ProbeResult(
    val available: Boolean,
    val reason: String,
)

/** Decides whether the Onyx raw pen path can be tried on this device. */
object OnyxProbe {
    fun available(): ProbeResult =
        evaluate(
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            sdkPresent =
                try {
                    Class.forName("com.onyx.android.sdk.pen.TouchHelper")
                    true
                } catch (_: ClassNotFoundException) {
                    false
                } catch (_: LinkageError) {
                    false
                },
        )

    internal fun evaluate(
        brand: String,
        manufacturer: String,
        sdkPresent: Boolean,
    ): ProbeResult =
        when {
            !brand.equals("ONYX", ignoreCase = true) && !manufacturer.equals("ONYX", ignoreCase = true) -> {
                ProbeResult(false, "not an Onyx device")
            }

            !sdkPresent -> {
                ProbeResult(false, "Onyx pen SDK classes not found")
            }

            else -> {
                ProbeResult(true, "Onyx device with pen SDK")
            }
        }
}
