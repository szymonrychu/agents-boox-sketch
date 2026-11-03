package io.github.szymonrychu.booxsketch.onyx

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
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
 *
 * One raw drawing helper exists per host view, as in the vendor demo: a repeated [start] on the
 * same view (every layout pass) only updates the limit and exclude rects, a different view closes
 * the old helper first, and [stop] closes it. A new helper is enabled only after the next frame
 * has drawn, followed by a full refresh. Pressure is normalised to 0..1 by [maxPressure],
 * the device maximum, so Onyx and touch strokes share one scale.
 */
class OnyxStrokeInput(
    @Suppress("unused") private val context: Context,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val rawDrawing: (View, RawInputCallback) -> RawDrawing = TouchHelperRawDrawing::create,
    private val maxPressure: () -> Float = Epd::maxTouchPressure,
    private val afterNextDraw: (View, Runnable) -> Unit = { v, r -> v.afterNextDraw(Handler(Looper.getMainLooper()), r) },
    private val fullRefresh: (View) -> Unit = Epd::fullRefresh,
) : StrokeInput,
    RawActivitySource {
    private val channel = Channel<StrokeEvent>(Channel.UNLIMITED)
    override val events: Flow<StrokeEvent> = channel.receiveAsFlow()
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null

    private var drawing: RawDrawing? = null
    private var host: View? = null
    private var paused = false
    private var holds = 0

    /** The session waiting for its first draw before raw drawing may be enabled, if any. */
    private var gated: RawDrawing? = null
    private var appliedLimit: Rect? = null
    private var appliedExclude: List<Rect> = emptyList()

    @Volatile
    private var pressureMax = FALLBACK_MAX_PRESSURE

    @Volatile
    private var tool = Tool.BALLPOINT

    @Volatile
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
                guard { strokeFrom(list.points, tool, width, newId(), pressureMax)?.let { channel.trySend(StrokeEvent.Committed(it)) } }
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
        val current = drawing
        if (current != null && host === view) {
            if (limit == appliedLimit && exclude == appliedExclude) return
            // As in the vendor demo, new rects take effect through setLimitRect followed by openRawDrawing.
            applyRects(current, limit, exclude)
            current.open()
            if (active()) enable(current) else current.setEnabled(false)
            return
        }
        current?.close()
        pressureMax = maxPressure()
        val d = rawDrawing(view, callback)
        drawing = d
        host = view
        applyRects(d, limit, exclude)
        applyBrush(d)
        d.open()
        // A new session (first start, or after the surface was re-created) must not freeze the
        // panel before the window has drawn: enable only after the next frame, then full refresh.
        // An outstanding hold (the UiRefresher's first-frame gate) already does the same. Only the
        // current session's draw releases its gate; a stale one (stopped or replaced) does nothing.
        if (holds == 0) {
            gated = d
            afterNextDraw(
                view,
                Runnable {
                    if (gated === d) {
                        gated = null
                        fullRefresh(view)
                        if (active()) enable(d)
                    }
                },
            )
        }
        d.setEnabled(active())
    }

    /** Raw drawing may be on: not paused, no [holdUi] outstanding and no session awaiting its first draw. */
    private fun active() = !paused && holds == 0 && gated == null

    override val rawActive: Boolean get() = drawing != null && active()

    private fun applyRects(
        d: RawDrawing,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        appliedLimit = Rect(limit)
        appliedExclude = exclude.map { Rect(it) }
        d.setLimitRect(limit, exclude.ifEmpty { listOf(Rect(NO_EXCLUDE)) })
    }

    private fun applyBrush(d: RawDrawing) {
        d.setBrush(BrushMapping.style(tool), BrushMapping.width(width))
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        this.tool = tool
        this.width = width
        drawing?.let { applyBrush(it) }
    }

    override fun pause() {
        paused = true
        drawing?.setEnabled(false)
    }

    /** Re-enables raw drawing; every enable resets the firmware brush, so it is applied again. */
    override fun resume() {
        paused = false
        if (active()) drawing?.let { enable(it) }
    }

    override val freezesUi: Boolean get() = true

    override fun holdUi() {
        holds++
        drawing?.setEnabled(false)
    }

    override fun releaseUi() {
        if (holds == 0) return
        holds--
        if (active()) drawing?.let { enable(it) }
    }

    override fun refresh(redraw: () -> Unit) {
        val d = drawing
        if (d == null || !active()) return redraw()
        d.setEnabled(false)
        try {
            redraw()
        } finally {
            enable(d)
        }
    }

    private fun enable(d: RawDrawing) {
        d.setEnabled(true)
        applyBrush(d)
    }

    override fun stop() {
        drawing?.close()
        drawing = null
        host = null
        gated = null
        appliedLimit = null
        appliedExclude = emptyList()
    }

    companion object {
        private const val DEFAULT_WIDTH = 4f
        private const val MIN_ERASE_RADIUS = 8f

        /**
         * Sent instead of an empty exclude list, which the SDK ignores (the old exclude would stay
         * active natively): one pixel just outside the host view, so nothing on the board is excluded.
         */
        internal val NO_EXCLUDE = Rect(-1, -1, 0, 0)

        /** Used when the device does not report its maximum pen pressure (Note Air 6C: 4096 levels). */
        const val FALLBACK_MAX_PRESSURE = 4096f

        /** Raw [pressure] scaled into 0..1 by [max]; an unknown (<= 0 or NaN) [max] uses [FALLBACK_MAX_PRESSURE]. */
        fun normalisePressure(
            pressure: Float,
            max: Float,
        ): Float {
            val m = if (max.isNaN() || max <= 0f) FALLBACK_MAX_PRESSURE else max
            return (pressure / m).coerceIn(0f, 1f)
        }

        /**
         * Converts the SDK's point list; null when empty. Times are relative to the first point and
         * pressure is normalised by [maxPressure] (1 for points already in 0..1).
         */
        internal fun strokeFrom(
            points: List<TouchPoint>,
            tool: Tool,
            width: Float,
            id: String,
            maxPressure: Float = 1f,
        ): Stroke? {
            if (points.isEmpty()) return null
            val t0 = points.first().timestamp
            val mapped =
                points.map {
                    val tilted = it.tiltX != 0 || it.tiltY != 0
                    Point(
                        x = it.x,
                        y = it.y,
                        pressure = normalisePressure(it.pressure, maxPressure),
                        tMs = (it.timestamp - t0).coerceAtLeast(0L),
                        tiltX = if (tilted) it.tiltX.toFloat() else null,
                        tiltY = if (tilted && it.tiltY != 0) it.tiltY.toFloat() else null,
                    )
                }
            return Stroke(id, tool, width, mapped)
        }
    }
}
