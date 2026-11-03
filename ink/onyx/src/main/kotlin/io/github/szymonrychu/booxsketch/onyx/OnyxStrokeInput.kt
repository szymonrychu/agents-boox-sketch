package io.github.szymonrychu.booxsketch.onyx

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.github.szymonrychu.booxsketch.ink.RawActivitySource
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * Pen input through the Onyx raw drawing SDK: the SDK renders ink immediately while the pen is
 * down and hands over the finished point list, which becomes a [Stroke]. Committed strokes are
 * drawn by the app's own renderer.
 */
class OnyxStrokeInput(
    @Suppress("unused") private val context: Context,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : StrokeInput,
    RawActivitySource {
    private val channel = Channel<StrokeEvent>(Channel.UNLIMITED)
    override val events: Flow<StrokeEvent> = channel.receiveAsFlow()
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null

    private var helper: TouchHelper? = null
    private var tool = Tool.BALLPOINT
    private var width = DEFAULT_WIDTH

    private val callback =
        object : RawInputCallback() {
            override fun onBeginRawDrawing(
                shortcutDrawing: Boolean,
                point: TouchPoint,
            ) = activity()

            override fun onEndRawDrawing(
                outLimitRegion: Boolean,
                point: TouchPoint,
            ) = activity()

            override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) = activity()

            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
                activity()
                guard { strokeFrom(list.points, tool, width, newId())?.let { channel.trySend(StrokeEvent.Committed(it)) } }
            }

            override fun onBeginRawErasing(
                shortcutErasing: Boolean,
                point: TouchPoint,
            ) = activity()

            override fun onEndRawErasing(
                outLimitRegion: Boolean,
                point: TouchPoint,
            ) = activity()

            override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) = activity()

            override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
                activity()
                val radius = maxOf(width, MIN_ERASE_RADIUS)
                list.points.forEach { channel.trySend(StrokeEvent.Erase(it.x, it.y, radius)) }
            }
        }

    private fun activity() {
        onRawActivity?.invoke()
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            onFailure?.invoke(e) ?: throw e
        } catch (e: LinkageError) {
            onFailure?.invoke(e) ?: throw e
        }
    }

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        val h = TouchHelper.create(view, callback)
        helper = h
        h.setLimitRect(limit, exclude)
        applyBrush(h)
        h.openRawDrawing()
        h.setRawDrawingEnabled(true)
    }

    private fun applyBrush(h: TouchHelper) {
        h.setStrokeStyle(BrushMapping.style(tool))
        h.setStrokeWidth(BrushMapping.width(width))
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        this.tool = tool
        this.width = width
        helper?.let { applyBrush(it) }
    }

    override fun pause() {
        helper?.setRawDrawingEnabled(false)
    }

    override fun resume() {
        helper?.setRawDrawingEnabled(true)
    }

    override fun stop() {
        helper?.closeRawDrawing()
        helper = null
    }

    companion object {
        private const val DEFAULT_WIDTH = 4f
        private const val MIN_ERASE_RADIUS = 8f

        /** Converts the SDK's point list; null when empty. Times are relative to the first point. */
        internal fun strokeFrom(
            points: List<TouchPoint>,
            tool: Tool,
            width: Float,
            id: String,
        ): Stroke? {
            if (points.isEmpty()) return null
            val t0 = points.first().timestamp
            val mapped =
                points.map {
                    val tilted = it.tiltX != 0 || it.tiltY != 0
                    Point(
                        x = it.x,
                        y = it.y,
                        pressure = it.pressure,
                        tMs = (it.timestamp - t0).coerceAtLeast(0L),
                        tiltX = if (tilted) it.tiltX.toFloat() else null,
                        tiltY = if (tilted && it.tiltY != 0) it.tiltY.toFloat() else null,
                    )
                }
            return Stroke(id, tool, width, mapped)
        }
    }
}
