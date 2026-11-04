package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper

/** The raw drawing calls [OnyxStrokeInput] makes, so its session logic can be tested without a device. */
interface RawDrawing {
    /** [limit] and [exclude] are in the pixels of the host view. */
    fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    )

    fun open()

    fun setEnabled(enabled: Boolean)

    /** Live ink style, width and ARGB [color]; an enable resets the firmware brush, so callers re-apply it. */
    fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    )

    fun close()
}

/** [RawDrawing] backed by one vendor [TouchHelper]. */
class TouchHelperRawDrawing(
    private val helper: TouchHelper,
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

    companion object {
        fun create(
            view: View,
            callback: RawInputCallback,
        ): RawDrawing = TouchHelperRawDrawing(TouchHelper.create(view, callback))
    }
}
