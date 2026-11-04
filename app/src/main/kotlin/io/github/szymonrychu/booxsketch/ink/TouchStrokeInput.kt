package io.github.szymonrychu.booxsketch.ink

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * Plain Android touch input. Only the stylus (and its eraser end) draws; a finger is ignored
 * unless [fingerEnabled] says otherwise. Historical points of batched MOVE events are kept, and
 * AXIS_TILT is stored as [Point.tiltX]. Touch has no vendor ink, so the stroke being drawn is
 * published on [inProgress] at every down and move for the board to draw live (a shape drag the
 * same way, with the shape tool); it is committed on lift, before [inProgress] clears, so the
 * board never shows a frame without either. A live stroke dropped without a commit (cancel,
 * pause, stop) sends [StrokeEvent.Cancelled]. Both carry the selected colour.
 *
 * Events come from the view's touch listener and, through [feed], from a window callback that
 * sees them before any view; the copy of an event already handled from one source is not
 * handled again (it is still reported to [onShapeEvent], so Diagnostics can tell which source
 * sees a shape drag).
 */
class TouchStrokeInput(
    private val fingerEnabled: () -> Boolean = { false },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : StrokeInput,
    WindowTouchFeed {
    private val channel = Channel<StrokeEvent>(Channel.UNLIMITED)
    override val events: Flow<StrokeEvent> = channel.receiveAsFlow()
    private val live = MutableStateFlow<Stroke?>(null)
    override val inProgress: StateFlow<Stroke?> = live.asStateFlow()

    private var tool = Tool.BALLPOINT
    private var width = DEFAULT_WIDTH
    private var color = PenColor.BLACK
    private var limit = Rect()
    private var exclude: List<Rect> = emptyList()
    private var paused = false
    private var view: View? = null
    private var current: MutableList<Point>? = null
    private var erasing = false
    private var lastHandled: EventKey? = null

    override var onShapeEvent: ((DragSource) -> Unit)? = null

    @Volatile
    private var penDown = false

    override val strokeInProgress: Boolean get() = penDown

    /** When true the pen erases instead of drawing (the stylus eraser end always erases). */
    @Volatile
    var eraserMode = false

    @SuppressLint("ClickableViewAccessibility")
    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        this.view = view
        this.limit = limit
        this.exclude = exclude
        view.setOnTouchListener { _, ev -> feed(ev, DragSource.VIEW) }
    }

    /** [ev] in the pixels of the started view; false when not started or the event is not taken. */
    override fun feed(
        ev: MotionEvent,
        source: DragSource,
    ): Boolean {
        if (view == null) return false
        val key = EventKey.of(ev)
        val taken = key == lastHandled || onTouch(ev).also { if (it) lastHandled = key }
        if (taken && tool.isShape && !erasing) onShapeEvent?.invoke(source)
        return taken
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        this.tool = tool
        this.width = width
    }

    override fun setColor(color: PenColor) {
        this.color = color
    }

    override fun pause() {
        paused = true
        drop()
    }

    override fun resume() {
        paused = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun stop() {
        view?.setOnTouchListener(null)
        view = null
        drop()
    }

    private fun drop() {
        penDown = false
        val dropped = current
        current = null
        if (!dropped.isNullOrEmpty()) channel.trySend(StrokeEvent.Cancelled)
        live.value = null
    }

    private fun accepts(ev: MotionEvent): Boolean =
        when (ev.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            MotionEvent.TOOL_TYPE_FINGER -> fingerEnabled()
            else -> false
        }

    private fun allowed(
        x: Float,
        y: Float,
    ): Boolean = limit.contains(x.toInt(), y.toInt()) && exclude.none { it.contains(x.toInt(), y.toInt()) }

    private fun onTouch(ev: MotionEvent): Boolean {
        // Only the gesture of an accepted down: the window feed also sees moves of taps elsewhere.
        val strayGesture = ev.actionMasked != MotionEvent.ACTION_DOWN && !penDown
        if (paused || !accepts(ev) || strayGesture) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!allowed(ev.x, ev.y)) return false
                penDown = true
                erasing = eraserMode || ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                current = if (erasing) null else mutableListOf()
                collect(ev, includeHistory = false)
                publish()
            }

            MotionEvent.ACTION_MOVE -> {
                collect(ev, includeHistory = true)
                publish()
            }

            MotionEvent.ACTION_UP -> {
                penDown = false
                collect(ev, includeHistory = true)
                commit()
            }

            MotionEvent.ACTION_CANCEL -> {
                drop()
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun collect(
        ev: MotionEvent,
        includeHistory: Boolean,
    ) {
        if (includeHistory) {
            for (h in 0 until ev.historySize) {
                add(ev, ev.getHistoricalX(h), ev.getHistoricalY(h), ev.getHistoricalPressure(h), ev.getHistoricalEventTime(h), h)
            }
        }
        add(ev, ev.x, ev.y, ev.pressure, ev.eventTime, null)
    }

    private fun add(
        ev: MotionEvent,
        x: Float,
        y: Float,
        pressure: Float,
        time: Long,
        history: Int?,
    ) {
        if (!allowed(x, y)) return
        if (erasing) {
            channel.trySend(StrokeEvent.Erase(x, y, maxOf(width, MIN_ERASE_RADIUS)))
            return
        }
        val tilt =
            if (history ==
                null
            ) {
                ev.getAxisValue(MotionEvent.AXIS_TILT)
            } else {
                ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, history)
            }
        current?.add(Point(x, y, pressure, (time - ev.downTime).coerceAtLeast(0L), tilt.takeIf { it != 0f }))
    }

    private fun publish() {
        live.value = current?.takeIf { it.isNotEmpty() }?.let { Stroke(LIVE_ID, tool, width, it.toList(), color) }
    }

    private fun commit() {
        val pts = current
        current = null
        if (!pts.isNullOrEmpty()) channel.trySend(StrokeEvent.Committed(Stroke(newId(), tool, width, pts.toList(), color)))
        drop()
    }

    /** Identifies one MotionEvent across its window and view copies (positions rounded: the copies are offset separately). */
    private data class EventKey(
        val downTime: Long,
        val eventTime: Long,
        val action: Int,
        val historySize: Int,
        val x: Int,
        val y: Int,
    ) {
        companion object {
            fun of(ev: MotionEvent) =
                EventKey(ev.downTime, ev.eventTime, ev.actionMasked, ev.historySize, Math.round(ev.x), Math.round(ev.y))
        }
    }

    private companion object {
        const val DEFAULT_WIDTH = 4f
        const val MIN_ERASE_RADIUS = 8f
        const val LIVE_ID = "in-progress"
    }
}
