package io.github.szymonrychu.booxsketch.board

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.onyx.Epd
import io.github.szymonrychu.booxsketch.onyx.FullRefreshDebouncer
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.render.StrokeRenderer
import kotlin.math.min
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/** Maps between view pixels and page-canvas pixels: the page is fitted and centred in the view. */
class BoardGeometry(
    private val viewW: Int,
    private val viewH: Int,
    private val page: PageCanvas,
) {
    val scale: Float = if (viewW <= 0 || viewH <= 0) 1f else min(viewW.toFloat() / page.w, viewH.toFloat() / page.h)
    val dx: Float = (viewW - page.w * scale) / 2f
    val dy: Float = (viewH - page.h * scale) / 2f

    fun toCanvas(stroke: Stroke): Stroke =
        stroke.copy(
            width = stroke.width / scale,
            points = stroke.points.map { it.copy(x = (it.x - dx) / scale, y = (it.y - dy) / scale) },
        )
}

/**
 * Window-coordinate rects (toolbar, overlays) as exclude rects in the board view's own pixels,
 * which is what the Onyx `setLimitRect` expects (the vendor demo offsets by the view location).
 * Rects are clipped to the board; ones that miss it are dropped.
 */
internal fun excludeRectsInView(
    viewLeft: Int,
    viewTop: Int,
    viewW: Int,
    viewH: Int,
    inWindow: List<Rect>,
): List<Rect> =
    inWindow.mapNotNull { r ->
        Rect(r).apply { offset(-viewLeft, -viewTop) }.takeIf { it.intersect(0, 0, viewW, viewH) && !it.isEmpty }
    }

/** A GC full refresh follows the first show (load), a page switch and a clear of the shown page. */
internal fun needsFullRefresh(
    prevIndex: Int?,
    prevStrokes: List<Stroke>,
    index: Int,
    strokes: List<Stroke>,
): Boolean = prevIndex != index || (prevStrokes.isNotEmpty() && strokes.isEmpty())

/** Pauses [input] while the activity is paused or the window lost focus; resumes when both are back. */
class InputGate(
    private val input: StrokeInput,
) {
    private var resumed = true
    private var focused = true
    private var enabled = true

    fun setResumed(resumed: Boolean) {
        this.resumed = resumed
        apply()
    }

    fun setFocused(focused: Boolean) {
        this.focused = focused
        apply()
    }

    private fun apply() {
        val want = resumed && focused
        if (want == enabled) return
        enabled = want
        if (want) input.resume() else input.pause()
    }
}

/**
 * Surface the page strokes are drawn on. Every redraw runs inside [refresher] (the input's
 * refresh, which lifts the raw-drawing screen freeze), and a load, page switch or clear also
 * schedules a debounced GC full refresh.
 */
class BoardSurfaceView(
    context: Context,
    private val renderer: StrokeRenderer = CanvasStrokeRenderer(),
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private var strokes: List<Stroke> = emptyList()
    private var page: PageCanvas = PageCanvas(1, 1, 1f)
    private var shownIndex: Int? = null
    private var ready = false

    var refresher: (() -> Unit) -> Unit = { it() }
    var onSurfaceCreated: () -> Unit = {}
    var onSurfaceDestroyed: () -> Unit = {}
    var onFocus: (Boolean) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val fullRefresh =
        FullRefreshDebouncer(FULL_REFRESH_DELAY_MS, { r, d -> main.postDelayed(r, d) }, { main.removeCallbacks(it) }) {
            refresher { Epd.fullRefresh() }
        }

    init {
        holder.addCallback(this)
    }

    fun geometry() = BoardGeometry(width, height, page)

    fun show(
        page: PageCanvas,
        strokes: List<Stroke>,
        index: Int,
    ) {
        if (page == this.page && strokes == this.strokes && index == shownIndex) return
        val full = needsFullRefresh(shownIndex, this.strokes, index, strokes)
        this.page = page
        this.strokes = strokes
        shownIndex = index
        redraw()
        if (full) fullRefresh.request()
    }

    private fun redraw() {
        if (!ready) return
        refresher { draw() }
    }

    private fun draw() {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.WHITE)
            val g = geometry()
            renderer.draw(canvas, strokes, g.scale, g.dx, g.dy)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        onSurfaceCreated()
        redraw()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = redraw()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        fullRefresh.cancel()
        onSurfaceDestroyed()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        onFocus(hasWindowFocus)
    }

    companion object {
        const val FULL_REFRESH_DELAY_MS = 300L
    }
}

/**
 * The drawing board: a [SurfaceView] in an AndroidView showing the current page of [notebook], fed by [input].
 * Stroke and erase events are mapped to page-canvas pixels before reaching the callbacks.
 * [exclude] are window-coordinate rects (toolbar, overlays) where the pen must not draw; input
 * is (re)started on every layout and exclude change, closed when the surface goes away, and
 * paused while the activity is paused or the window has no focus.
 */
@Composable
fun BoardView(
    notebook: Notebook,
    input: StrokeInput,
    onStroke: (Stroke) -> Unit,
    onErase: (x: Float, y: Float, radius: Float) -> Unit,
    modifier: Modifier = Modifier,
    exclude: List<Rect> = emptyList(),
) {
    val surface = remember { arrayOfNulls<BoardSurfaceView>(1) }
    val strokeCb by rememberUpdatedState(onStroke)
    val eraseCb by rememberUpdatedState(onErase)
    val excludeNow by rememberUpdatedState(exclude)
    val gate = remember(input) { InputGate(input) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { gate.setResumed(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { gate.setResumed(true) }

    fun startOn(v: BoardSurfaceView) {
        if (v.width <= 0 || v.height <= 0) return
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        input.start(v, Rect(0, 0, v.width, v.height), excludeRectsInView(loc[0], loc[1], v.width, v.height, excludeNow))
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BoardSurfaceView(ctx).also { v ->
                surface[0] = v
                v.refresher = input::refresh
                v.onSurfaceCreated = { startOn(v) }
                v.onSurfaceDestroyed = { input.stop() }
                v.onFocus = gate::setFocused
                v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startOn(v) }
            }
        },
        update = { it.show(notebook.canvas, notebook.pages[notebook.current].strokes, notebook.current) },
        onRelease = { input.stop() },
    )
    LaunchedEffect(exclude) { surface[0]?.let { startOn(it) } }
    LaunchedEffect(input) {
        input.events.collect { ev ->
            val g = surface[0]?.geometry() ?: return@collect
            when (ev) {
                is StrokeEvent.Committed -> strokeCb(g.toCanvas(ev.stroke))
                is StrokeEvent.Erase -> eraseCb((ev.x - g.dx) / g.scale, (ev.y - g.dy) / g.scale, ev.radius / g.scale)
            }
        }
    }
}

/** Non-surface rendering of a page for previews and screenshot tests, where a SurfaceView does not draw. */
@Composable
fun BoardPreviewCanvas(
    canvas: PageCanvas,
    page: Page,
    modifier: Modifier = Modifier,
    renderer: StrokeRenderer = CanvasStrokeRenderer(),
) {
    Canvas(modifier.background(androidx.compose.ui.graphics.Color.White)) {
        val g = BoardGeometry(size.width.toInt(), size.height.toInt(), canvas)
        drawIntoCanvas { renderer.draw(it.nativeCanvas, page.strokes, g.scale, g.dx, g.dy) }
    }
}
