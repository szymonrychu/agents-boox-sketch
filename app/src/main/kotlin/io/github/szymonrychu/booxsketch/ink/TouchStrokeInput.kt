package io.github.szymonrychu.booxsketch.ink

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * Plain Android touch input. Only the stylus (and its eraser end) draws; a finger is ignored
 * unless [fingerEnabled] says otherwise. Historical points of batched MOVE events are kept, and
 * AXIS_TILT is stored as [Point.tiltX].
 */
class TouchStrokeInput(
    private val fingerEnabled: () -> Boolean = { false },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : StrokeInput {
    private val channel = Channel<StrokeEvent>(Channel.UNLIMITED)
    override val events: Flow<StrokeEvent> = channel.receiveAsFlow()

    private var tool = Tool.BALLPOINT
    private var width = DEFAULT_WIDTH
    private var limit = Rect()
    private var exclude: List<Rect> = emptyList()
    private var paused = false
    private var view: View? = null
    private var current: MutableList<Point>? = null
    private var erasing = false

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
        view.setOnTouchListener { _, ev -> onTouch(ev) }
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        this.tool = tool
        this.width = width
    }

    override fun pause() {
        paused = true
        current = null
    }

    override fun resume() {
        paused = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun stop() {
        view?.setOnTouchListener(null)
        view = null
        current = null
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
        if (paused || !accepts(ev)) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!allowed(ev.x, ev.y)) return false
                erasing = eraserMode || ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                current = if (erasing) null else mutableListOf()
                collect(ev, includeHistory = false)
            }

            MotionEvent.ACTION_MOVE -> {
                collect(ev, includeHistory = true)
            }

            MotionEvent.ACTION_UP -> {
                collect(ev, includeHistory = true)
                commit()
            }

            MotionEvent.ACTION_CANCEL -> {
                current = null
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

    private fun commit() {
        val pts = current ?: return
        current = null
        if (pts.isNotEmpty()) channel.trySend(StrokeEvent.Committed(Stroke(newId(), tool, width, pts.toList())))
    }

    private companion object {
        const val DEFAULT_WIDTH = 4f
        const val MIN_ERASE_RADIUS = 8f
    }
}
