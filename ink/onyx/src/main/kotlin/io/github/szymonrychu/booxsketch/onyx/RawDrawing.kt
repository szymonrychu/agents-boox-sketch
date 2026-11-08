package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper

/** The raw drawing calls [OnyxStrokeInput] makes, so its session logic can be tested without a device. */
@Suppress("TooManyFunctions") // one member per vendor TouchHelper call the session makes
interface RawDrawing {
    /** [limit] and [exclude] are in the pixels of the host view. */
    fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    )

    fun open()

    fun setEnabled(enabled: Boolean)

    /**
     * Live ink style, width and ARGB [color]. An open resets the firmware style and starts the pen, so callers
     * apply the brush right after every open and on every enable; never while a stroke is in progress.
     */
    fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    )

    fun close()

    /** How long after a pen-up without a new stroke the SDK reports the pen idle (`onPenUpRefresh`). */
    fun setPenUpRefreshTimeMs(ms: Int) = Unit

    /** `TouchHelper.setBrushRawDrawingEnabled`: the firmware brush (not eraser) raw drawing, [InkExperiments.vendorResumeOrder]. */
    fun setBrushRawDrawingEnabled(enabled: Boolean) = Unit

    /** Writes the firmware's own parameters of stroke [style] back (`Device.setStrokeParameters`), as the vendor demo does. */
    fun applyStrokeParameters(style: Int) = Unit

    /** `TouchHelper.forceSetRawDrawingEnabled`: the transition without the SDK's cached-flag short cut. */
    fun forceSetEnabled(enabled: Boolean) = Unit

    /** `TouchHelper.setRawDrawingRenderEnabled`: the vendor demo's render off/on around a brush change. */
    fun setRenderEnabled(enabled: Boolean) = Unit

    /** The firmware's per-pen-button region config for [limit] (host view pixels), [InkExperiments.regionPenConfig]. */
    fun setRegionPenConfig(
        limit: Rect,
        configs: List<PenStrokeConfig>,
    ) = Unit

    fun clearRegionPenConfig() = Unit
}

/** [RawDrawing] backed by one vendor [TouchHelper]. */
class TouchHelperRawDrawing(
    private val helper: TouchHelper,
    private val view: View? = null,
) : RawDrawing {
    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) {
        helper.setLimitRect(limit, exclude)
    }

    override fun open() {
        helper.openRawDrawing()
    }

    override fun setEnabled(enabled: Boolean) {
        helper.setRawDrawingEnabled(enabled)
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) {
        helper.setStrokeStyle(style)
        helper.setStrokeWidth(width)
        helper.setStrokeColor(color)
    }

    override fun close() {
        helper.closeRawDrawing()
    }

    override fun setPenUpRefreshTimeMs(ms: Int) {
        helper.setPenUpRefreshTimeMs(ms)
    }

    override fun setBrushRawDrawingEnabled(enabled: Boolean) {
        helper.setBrushRawDrawingEnabled(enabled)
    }

    override fun applyStrokeParameters(style: Int) {
        Epd.strokeParameters(style)?.let { Epd.setStrokeParameters(style, it) }
    }

    override fun forceSetEnabled(enabled: Boolean) {
        helper.forceSetRawDrawingEnabled(enabled)
    }

    override fun setRenderEnabled(enabled: Boolean) {
        helper.setRawDrawingRenderEnabled(enabled)
    }

    /** The region config takes no view, so [limit] is moved to screen pixels as the SDK does for the limit rect. */
    override fun setRegionPenConfig(
        limit: Rect,
        configs: List<PenStrokeConfig>,
    ) {
        val screen = Rect(limit)
        view?.let {
            val at = IntArray(2)
            it.getLocationOnScreen(at)
            screen.offset(at[0], at[1])
        }
        Epd.setRegionPenConfig(screen, configs)
    }

    override fun clearRegionPenConfig() {
        Epd.clearRegionPenConfig()
    }

    companion object {
        fun create(
            view: View,
            callback: RawInputCallback,
        ): RawDrawing = TouchHelperRawDrawing(TouchHelper.create(view, callback), view)
    }
}
