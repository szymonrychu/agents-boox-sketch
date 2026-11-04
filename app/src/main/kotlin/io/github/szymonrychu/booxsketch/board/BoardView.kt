package io.github.szymonrychu.booxsketch.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
import android.graphics.Canvas as AndroidCanvas
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

/** True when [new] is [old] plus at least one appended stroke (nothing removed or changed). */
internal fun isAppend(
    old: List<Stroke>,
    new: List<Stroke>,
): Boolean = new.size > old.size && new.subList(0, old.size) == old

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
 * schedules a debounced GC full refresh. [showLive] draws the stroke in progress of an input
 * without vendor ink (touch) on top of the page while the pen moves, in the e-ink handwriting
 * update mode; no animation, one frame per input event. A shape drag's snapped preview is part
 * of [show], so the commit swaps preview for shape in one frame.
 */
class BoardSurfaceView(
    context: Context,
    private val renderer: StrokeRenderer = CanvasStrokeRenderer(),
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private var strokes: List<Stroke> = emptyList()
    private var page: PageCanvas = PageCanvas(1, 1, 1f)
    private var shownIndex: Int? = null
    private var preview: Stroke? = null
    private var background: Bitmap? = null
    private var ready = false
    private var live: Stroke? = null
    private var handwriting = false

    /** Where frames are drawn; tests swap it because a Robolectric surface has no canvas. */
    internal var canvasSource: (SurfaceHolder) -> AndroidCanvas? = { it.lockCanvas() }

    /** True between surfaceCreated and surfaceDestroyed; raw drawing may only be open then. */
    val surfaceReady: Boolean get() = ready

    var refresher: (() -> Unit) -> Unit = { it() }

    /** Runs the redraw after strokes were only added to the shown page; see [StrokeInput.drawCommitted]. */
    var committer: (() -> Unit) -> Unit = { it() }
    var onSurfaceCreated: () -> Unit = {}
    var onSurfaceDestroyed: () -> Unit = {}
    var onFocus: (Boolean) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val fullRefresh =
        FullRefreshDebouncer(FULL_REFRESH_DELAY_MS, { r, d -> main.postDelayed(r, d) }, { main.removeCallbacks(it) }) {
            refresher { Epd.fullRefresh(this) }
        }

    init {
        holder.addCallback(this)
    }

    fun geometry() = BoardGeometry(width, height, page)

    /**
     * Shows [strokes] of page [index] plus the shape [preview] (page-canvas pixels, drawn over the page). A [background]
     * (a document page, in page-canvas pixels) is drawn first; another background instance counts as a page change.
     */
    fun show(
        page: PageCanvas,
        strokes: List<Stroke>,
        index: Int,
        preview: Stroke? = null,
        background: Bitmap? = null,
    ) {
        val sameBackground = background === this.background
        val samePage = page == this.page && strokes == this.strokes && index == shownIndex && sameBackground
        if (samePage && preview == this.preview) return
        val appended = sameBackground && onlyAppended(page, strokes, index, preview)
        val full = needsFullRefresh(shownIndex, this.strokes, index, strokes) || (shownIndex != null && !sameBackground)
        val dragging = preview != null && samePage
        this.background = background
        this.page = page
        this.strokes = strokes
        shownIndex = index
        this.preview = preview
        if (dragging) handwritingMode()
        redraw(appended)
        if (full) fullRefresh.request()
    }

    /** True when the new state differs from the shown one only by strokes added to the same page. */
    private fun onlyAppended(
        page: PageCanvas,
        strokes: List<Stroke>,
        index: Int,
        preview: Stroke?,
    ) = page == this.page && index == shownIndex && preview == this.preview && isAppend(this.strokes, strokes)

    /**
     * Shows [stroke] (view pixels) over the page right away. Null only forgets it: the stroke is
     * committed into the page next, and that redraw replaces it without a blank frame; a
     * cancelled stroke disappears with the next redraw.
     */
    fun showLive(stroke: Stroke?) {
        live = stroke
        if (stroke == null) return
        handwritingMode()
        redraw()
    }

    private fun handwritingMode() {
        if (handwriting) return
        handwriting = true
        Epd.handwritingMode(this)
    }

    private fun redraw(appended: Boolean = false) {
        if (!ready) return
        (if (appended) committer else refresher) { draw() }
    }

    private fun draw() {
        val canvas = canvasSource(holder) ?: return
        try {
            canvas.drawColor(Color.WHITE)
            val g = geometry()
            background?.let {
                val m =
                    Matrix().apply {
                        postScale(g.scale * page.w / it.width, g.scale * page.h / it.height)
                        postTranslate(g.dx, g.dy)
                    }
                canvas.drawBitmap(it, m, null)
            }
            renderer.draw(canvas, strokes, g.scale, g.dx, g.dy)
            preview?.let { renderer.draw(canvas, listOf(it), g.scale, g.dx, g.dy) }
            live?.let { renderer.draw(canvas, listOf(it), 1f, 0f, 0f) }
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
 * Stroke and erase events are mapped to page-canvas pixels before reaching the callbacks. A stroke
 * in progress with a shape tool is not drawn as ink: it goes to [onShapeDrag] (page-canvas pixels)
 * and the board shows [preview], the snapped shape. Only the ordered [StrokeInput.events] end a
 * drag: a commit reaches [onStroke] (which drops the preview), a [StrokeEvent.Cancelled] calls
 * [onShapeCancel]; the in-progress stream going null never does, so a lift cannot clear the
 * preview a frame before the shape is committed.
 * [exclude] are window-coordinate rects (toolbar, overlays) where the pen must not draw; input
 * is (re)started on every layout and exclude change, closed when the surface goes away, and
 * paused while the activity is paused or the window has no focus.
 */
@Suppress("LongParameterList")
@Composable
fun BoardView(
    notebook: Notebook,
    input: StrokeInput,
    onStroke: (Stroke) -> Unit,
    onErase: (x: Float, y: Float, radius: Float) -> Unit,
    modifier: Modifier = Modifier,
    exclude: List<Rect> = emptyList(),
    preview: Stroke? = null,
    onShapeDrag: (Stroke) -> Unit = {},
    onShapeCancel: () -> Unit = {},
    background: Bitmap? = null,
) {
    val surface = remember { arrayOfNulls<BoardSurfaceView>(1) }
    val strokeCb by rememberUpdatedState(onStroke)
    val eraseCb by rememberUpdatedState(onErase)
    val shapeDragCb by rememberUpdatedState(onShapeDrag)
    val shapeCancelCb by rememberUpdatedState(onShapeCancel)
    val excludeNow by rememberUpdatedState(exclude)
    val gate = remember(input) { InputGate(input) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { gate.setResumed(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { gate.setResumed(true) }

    fun startOn(v: BoardSurfaceView) = v.startInput(input, excludeNow)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BoardSurfaceView(ctx).also { v ->
                surface[0] = v
                v.refresher = input::refresh
                v.committer = input::drawCommitted
                v.onSurfaceCreated = { startOn(v) }
                v.onSurfaceDestroyed = { input.stop() }
                v.onFocus = gate::setFocused
                v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startOn(v) }
            }
        },
        update = { it.show(notebook.canvas, notebook.pages[notebook.current].strokes, notebook.current, preview, background) },
        onRelease = { input.stop() },
    )
    LaunchedEffect(exclude) { surface[0]?.let { startOn(it) } }
    LaunchedEffect(input) {
        input.inProgress.collect { s ->
            val v = surface[0]
            // The end of a drag never touches the preview here: a lift clears it in onStroke, a drop on Cancelled.
            if (s != null && s.tool.isShape) {
                v?.geometry()?.let { shapeDragCb(it.toCanvas(s)) }
            } else {
                v?.showLive(s)
            }
        }
    }
    LaunchedEffect(input) {
        input.events.collect { ev ->
            if (ev is StrokeEvent.Cancelled) return@collect shapeCancelCb()
            val g = surface[0]?.geometry() ?: return@collect
            when (ev) {
                is StrokeEvent.Committed -> strokeCb(g.toCanvas(ev.stroke))
                is StrokeEvent.Erase -> eraseCb((ev.x - g.dx) / g.scale, (ev.y - g.dy) / g.scale, ev.radius / g.scale)
                StrokeEvent.Cancelled -> Unit
            }
        }
    }
}

/**
 * (Re)starts [input] on this view with [exclude] window rects. Does nothing while the surface does
 * not exist (a layout or exclude change after surfaceDestroyed must not reopen raw drawing) or the
 * view has no size; surfaceCreated calls it again.
 */
internal fun BoardSurfaceView.startInput(
    input: StrokeInput,
    exclude: List<Rect>,
) {
    if (!surfaceReady || width <= 0 || height <= 0) return
    val loc = IntArray(2).also { getLocationInWindow(it) }
    input.start(this, Rect(0, 0, width, height), excludeRectsInView(loc[0], loc[1], width, height, exclude))
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
