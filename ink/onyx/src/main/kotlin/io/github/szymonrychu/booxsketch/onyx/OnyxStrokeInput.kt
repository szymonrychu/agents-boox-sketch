package io.github.szymonrychu.booxsketch.onyx

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.data.TouchPointList
import io.github.szymonrychu.booxsketch.ink.CommittedInkSurface
import io.github.szymonrychu.booxsketch.ink.RawActivitySource
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Locale
import java.util.UUID

/**
 * Pen input through the Onyx raw drawing SDK: the SDK renders ink immediately while the pen is
 * down and hands over the finished point list, which becomes a [Stroke]. Committed strokes are
 * drawn by the app's own renderer, but not while raw drawing is live ([rawLive]): the host view,
 * when it is a [CommittedInkSurface], is synced when the pen goes idle (the SDK's pen-up refresh,
 * [PEN_IDLE_MS] after the last pen-up) and right before raw drawing turns off or closes.
 *
 * One raw drawing helper exists per host view, as in the vendor demo: a repeated [start] on the
 * same view (every layout pass) only updates the limit and exclude rects, a different view closes
 * the old helper first, and [stop] closes it. A new helper is enabled only after the next frame
 * has drawn, followed by a full refresh. Pressure is normalised to 0..1 by [maxPressure],
 * the device maximum, so Onyx and touch strokes share one scale.
 *
 * A shape tool keeps raw drawing off for as long as it is selected (part of [active], not a
 * nested hold, so it cannot get stuck): the shape drag is drawn by the app from plain touch
 * events, and raw ink must not draw freehand on top. The eraser ([setEraser]) keeps raw drawing off
 * the same way: the pen tip then erases through plain touch events. The selected colour is part of
 * the raw brush.
 *
 * The brush (style, width, colour) is applied only while no stroke is in progress: before the first open,
 * on every enable (every open resets the firmware stroke style to 0, the SDK's `resetStrokeStyle`, and raw
 * drawing is always enabled again before any ink), and on a tool or colour change. Never from a stroke
 * callback: those run after the firmware has started the stroke, and a brush write then corrupts its live
 * ink (v0.14.2). These are the v0.13.2 semantics, the version proven on the device. Opens, enables and
 * brushes are logged under [InkDiag].
 */
@Suppress("TooManyFunctions") // every StrokeInput member plus the raw session steps (open, enable, disable, brush)
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
    private var penDown = false

    override val strokeInProgress: Boolean get() = penDown

    @Volatile
    private var pressureMax = FALLBACK_MAX_PRESSURE

    @Volatile
    private var tool = Tool.BALLPOINT

    @Volatile
    private var width = DEFAULT_WIDTH

    @Volatile
    private var color = PenColor.BLACK

    @Volatile
    private var eraser = false

    private val callback =
        object : RawInputCallback() {
            override fun onBeginRawDrawing(
                shortcutDrawing: Boolean,
                point: TouchPoint,
            ) {
                penDown = true
                activity()
            }

            override fun onEndRawDrawing(
                outLimitRegion: Boolean,
                point: TouchPoint,
            ) {
                penDown = false
                activity()
            }

            override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) = activity()

            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
                activity()
                guard {
                    strokeFrom(
                        list.points,
                        tool,
                        width,
                        newId(),
                        pressureMax,
                        color,
                    )?.let { channel.trySend(StrokeEvent.Committed(it)) }
                }
            }

            override fun onBeginRawErasing(
                shortcutErasing: Boolean,
                point: TouchPoint,
            ) {
                penDown = true
                activity()
            }

            override fun onEndRawErasing(
                outLimitRegion: Boolean,
                point: TouchPoint,
            ) {
                penDown = false
                activity()
            }

            override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) = activity()

            /** The pen went idle: the SDK re-arms this after every pen-up and cancels it on a new press. */
            override fun onPenUpRefresh(refreshRect: RectF) {
                val sync = Runnable { if (!penDown) syncHost() }
                val main = Looper.getMainLooper()
                if (Looper.myLooper() == main) sync.run() else Handler(main).post(sync)
            }

            override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
                activity()
                val radius = maxOf(width, MIN_ERASE_RADIUS)
                list.points.forEach { channel.trySend(StrokeEvent.Erase(it.x, it.y, radius)) }
            }
        }

    private fun activity() {
        onRawActivity?.invoke()
    }

    /** Lets the host surface draw the committed ink the overlay still holds; call before the overlay goes. */
    private fun syncHost() {
        (host as? CommittedInkSurface)?.syncCommitted()
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
            syncHost()
            applyRects(current, limit, exclude)
            open(current)
            if (active()) enable(current) else disable(current)
            return
        }
        current?.let { close(it) }
        pressureMax = maxPressure()
        val d = rawDrawing(view, callback)
        drawing = d
        host = view
        applyRects(d, limit, exclude)
        applyBrush(d)
        open(d)
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

    /**
     * Opens raw drawing; the pen idle timeout is set after every open, as the SDK may reset it. The open
     * resets the firmware stroke style, which the enable that always follows before any ink applies again.
     */
    private fun open(d: RawDrawing) {
        d.open()
        d.setPenUpRefreshTimeMs(PEN_IDLE_MS)
        InkDiag.info("raw open")
    }

    /** Raw drawing may be on: not paused, no [holdUi] outstanding, no session awaiting its first draw, no shape tool, no eraser. */
    private fun active() = !paused && holds == 0 && gated == null && !tool.isShape && !eraser

    override val rawActive: Boolean get() = drawing != null && active()

    override val rawLive: Boolean get() = rawActive

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
        val style = BrushMapping.style(tool)
        val w = BrushMapping.width(tool, width)
        val argb = color.argb
        d.setBrush(style, w, argb)
        InkDiag.info("setBrush style=$style width=$w color=#${"%08X".format(Locale.ROOT, argb)} tool=$tool")
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        val wasShape = this.tool.isShape
        this.tool = tool
        this.width = width
        val d = drawing ?: return
        when {
            tool.isShape && !wasShape -> disable(d)
            !tool.isShape && wasShape && active() -> enable(d)
            else -> applyBrush(d)
        }
    }

    override fun setColor(color: PenColor) {
        this.color = color
        drawing?.let { applyBrush(it) }
    }

    override fun setEraser(on: Boolean) {
        if (on == eraser) return
        eraser = on
        val d = drawing ?: return
        if (on) {
            disable(d)
        } else if (active()) {
            enable(d)
        }
    }

    override fun pause() {
        paused = true
        drawing?.let { disable(it) }
    }

    /** Re-enables raw drawing; every enable resets the firmware brush, so it is applied again. */
    override fun resume() {
        paused = false
        if (active()) drawing?.let { enable(it) }
    }

    override val freezesUi: Boolean get() = true

    override fun holdUi() {
        holds++
        drawing?.let { disable(it) }
    }

    override fun releaseUi() {
        if (holds == 0) return
        holds--
        if (active()) drawing?.let { enable(it) }
    }

    override fun refresh(redraw: () -> Unit) {
        val d = drawing
        if (d == null || !active()) return redraw()
        disable(d)
        try {
            redraw()
        } finally {
            enable(d)
        }
    }

    /**
     * Raw drawing off: the host surface is synced first, because the overlay with the committed
     * ink goes with it. The SDK sends no end callback for a stroke it cuts, so the pen is no longer down.
     */
    private fun disable(d: RawDrawing) {
        syncHost()
        penDown = false
        d.setEnabled(false)
    }

    private fun close(d: RawDrawing) {
        syncHost()
        penDown = false
        d.close()
    }

    private fun enable(d: RawDrawing) {
        d.setEnabled(true)
        InkDiag.info("raw enable")
        applyBrush(d)
    }

    override fun stop() {
        drawing?.let { close(it) }
        penDown = false
        drawing = null
        host = null
        gated = null
        appliedLimit = null
        appliedExclude = emptyList()
    }

    companion object {
        private const val DEFAULT_WIDTH = 4f
        private const val MIN_ERASE_RADIUS = 8f

        /** Pen idle timeout: long enough that a pause between letters does not count as idle. */
        const val PEN_IDLE_MS = 700

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
            color: PenColor = PenColor.BLACK,
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
            return Stroke(id, tool, width, mapped, color)
        }
    }
}
