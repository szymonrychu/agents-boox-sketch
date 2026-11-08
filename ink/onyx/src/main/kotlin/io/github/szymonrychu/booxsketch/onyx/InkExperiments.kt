package io.github.szymonrychu.booxsketch.onyx

import android.os.Handler
import android.os.Looper

/**
 * The live ink experiments of the red-ink diagnostic build (T-78), switched in the Diagnostics tab. Both off (the
 * default) is the v0.18.0 call sequence with every open paused. [vendorResumeOrder] (H2): every enable applies the
 * brush before the transition, as the vendor pen demo does, after a 500 ms delay on colour devices; a tool or colour
 * change while live is followed by a render off/on pair. [regionPenConfig] (H1): after every open, enable and brush
 * change the current brush is also written into the firmware's per-pen-button region config, which is cleared on close.
 */
data class InkExperiments(
    val vendorResumeOrder: Boolean = DEFAULT_ON,
    val regionPenConfig: Boolean = DEFAULT_ON,
) {
    companion object {
        /** The default of both toggles; the one line to flip for a build that ships with them on. */
        const val DEFAULT_ON = false

        /** Both toggles off: the v0.18.0 call sequence with every open paused. */
        val OFF = InkExperiments(vendorResumeOrder = false, regionPenConfig = false)
    }
}

/**
 * What [OnyxStrokeInput] needs to run the [InkExperiments]: the current [toggles] (read at every open, enable and
 * brush change, so a switch takes effect at the next one), whether the device has a colour screen, the firmware's
 * pen button names (`android.onyx.handwriting.PenButton`, from [FirmwareBrushApi]) and a delayed main-thread post.
 */
class InkExperimentEnv(
    val toggles: () -> InkExperiments = { InkExperiments.OFF },
    val colorDevice: () -> Boolean = Epd::isColorDevice,
    val penButtons: () -> List<String> = FirmwareBrushApi::penButtons,
    val postDelayed: (Runnable, Long) -> Unit = { r, ms -> Handler(Looper.getMainLooper()).postDelayed(r, ms) },
)

/** One firmware pen button's live ink config (`StrokeConfig`): [penButton] is a `PenButton` static field name. */
data class PenStrokeConfig(
    val penButton: String,
    val style: Int,
    val width: Float,
    val color: Int,
)
