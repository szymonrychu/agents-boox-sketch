package io.github.szymonrychu.booxsketch.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.szymonrychu.booxsketch.ink.CommittedInkSurface
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.onyx.BrushMapping
import io.github.szymonrychu.booxsketch.onyx.Epd
import io.github.szymonrychu.booxsketch.onyx.FullRefreshDebouncer
import io.github.szymonrychu.booxsketch.onyx.InkDiag
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.render.StrokeRenderer
import io.github.szymonrychu.booxsketch.render.devicePenRenderer
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas
import io.github.szymonrychu.booxsketch.render.inkBounds as strokeInkBounds

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

    /** View pixels [stroke] (page-canvas pixels) can paint; see [strokeInkBounds]. */
    fun inkBounds(stroke: Stroke): RectF? = strokeInkBounds(stroke, scale, dx, dy)
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

/** The strokes the board draws for the selected page: consumed ones (transcribed into text runs) never are. */
internal fun shownStrokes(n: Notebook): List<Stroke> = n.pages[n.current].visibleStrokes

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

/** "l,t,r,b (w x h)" of [r] in whole pixels, or "none". */
private fun box(r: RectF?): String =
    r?.let {
        "${it.left.roundToInt()},${it.top.roundToInt()},${it.right.roundToInt()},${it.bottom.roundToInt()} " +
            "(${it.width().roundToInt()}x${it.height().roundToInt()})"
    } ?: "none"

/**
 * The [InkDiag] line of one committed stroke: the box of its raw input points ([raw], view
 * pixels as the input delivered them), the bounds the board redraws it in ([ink], view pixels),
 * the view size and its position on the screen, and the firmware's stroke parameters of its style.
 */
internal fun strokeDiagLine(
    raw: Stroke,
    ink: RectF?,
    viewW: Int,
    viewH: Int,
    origin: PointF,
    params: FloatArray?,
): String {
    val rawBox =
        raw.points.takeIf { it.isNotEmpty() }?.let { pts ->
            RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
    return "stroke ${raw.tool} width ${raw.width} points ${raw.points.size} raw ${box(rawBox)} ink ${box(ink)} " +
        "view ${viewW}x$viewH at ${origin.x.roundToInt()},${origin.y.roundToInt()} " +
        "params ${params?.joinToString(",", "[", "]") ?: "none"}"
}

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
 * Recognises a finger tap: a finger ACTION_UP within [slop] px and [timeoutMs] of its ACTION_DOWN,
 * with a single pointer and no stroke in progress at either end. Stylus taps are strokes, and on
 * inputs that draw with the finger a finger tap is a stroke too.
 */
internal class TapDetector(
    private val slop: Float,
    private val timeoutMs: Long = TAP_TIMEOUT_MS,
) {
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** Feeds [ev]; true when it ends a tap. */
    fun onEvent(
        ev: MotionEvent,
        strokeInProgress: Boolean,
    ): Boolean {
        val finger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = finger && !strokeInProgress
                downX = ev.x
                downY = ev.y
                downAt = ev.eventTime
                false
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking && !near(ev)) tracking = false
                false
            }

            MotionEvent.ACTION_UP -> {
                val tap = tracking && finger && !strokeInProgress && near(ev) && ev.eventTime - downAt <= timeoutMs
                tracking = false
                tap
            }

            else -> {
                // A second pointer or a cancel ends the gesture as a non-tap.
                tracking = false
                false
            }
        }
    }

    private fun near(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        return dx * dx + dy * dy <= slop * slop
    }

    companion object {
        const val TAP_TIMEOUT_MS = 300L
    }
}

/**
 * Surface the page strokes are drawn on. Every redraw runs inside [refresher] (the input's
 * refresh, which lifts the raw-drawing screen freeze), and a load, page switch or clear also
 * schedules a debounced GC full refresh. Strokes appended while [rawLive] are not drawn at all:
 * the raw drawing overlay holds them, and a frame posted then stalls the next stroke's ink. Their
 * dirty rect is drawn by [syncCommitted] (pen idle, or before raw drawing turns off) and before any
 * other change is shown, so the surface holds every committed stroke before the overlay goes.
 * [showLive] draws the stroke in progress of an input without vendor ink (touch) on top of the
 * page while the pen moves, in the e-ink handwriting update mode; no animation, one frame per
 * input event. A shape drag's snapped preview is part of [show], so the commit swaps preview for
 * shape in one frame. The handwriting mode is near-binary (no grey, anti-aliasing or texture), so
 * it is reset when the stroke or drag that set it ends, before the frame that shows the result
 * ([einkMode]). A finger tap calls [onTap]; it is
 * seen in [dispatchTouchEvent], before whichever touch listener the input installed.
 */
@Suppress("TooManyFunctions") // surface callbacks, frame drawing and the dismiss gesture
class BoardSurfaceView(
    context: Context,
    renderer: StrokeRenderer? = null,
) : SurfaceView(context),
    SurfaceHolder.Callback,
    CommittedInkSurface,
    TextLayerSurface {
    private val renderer: StrokeRenderer = renderer ?: CanvasStrokeRenderer(devicePenRenderer(::screenOrigin))
    private val grid = BoardGrid()
    private var strokes: List<Stroke> = emptyList()
    private var page: PageCanvas = PageCanvas(1, 1, 1f)
    private var shownIndex: Int? = null
    private var preview: Stroke? = null
    private var background: Bitmap? = null
    private var textLayer: Bitmap? = null
    private var ready = false
    private var live: Stroke? = null
    private var handwriting = false

    /** View pixels of strokes shown in [strokes] but not yet drawn on the surface. */
    private var pendingDirty: Rect? = null

    /** Where frames are drawn; tests swap it because a Robolectric surface has no canvas. */
    internal var canvasSource: (SurfaceHolder) -> AndroidCanvas? = { it.lockCanvas() }

    /** Where dirty-rect frames are drawn; the rect is the requested one and may be widened by the lock. */
    internal var dirtyCanvasSource: (SurfaceHolder, Rect) -> AndroidCanvas? = { h, r -> h.lockCanvas(r) }

    /** True between surfaceCreated and surfaceDestroyed; raw drawing may only be open then. */
    val surfaceReady: Boolean get() = ready

    var refresher: (() -> Unit) -> Unit = { it() }

    /** Switches the e-ink update mode of this view: true is the handwriting mode, false resets it; tests record it. */
    internal var einkMode: (Boolean) -> Unit = { on -> if (on) Epd.handwritingMode(this) else Epd.resetUpdateMode(this) }

    /** True while the input's raw drawing overlay draws the ink; see [StrokeInput.rawLive]. */
    var rawLive: () -> Boolean = { false }

    var onSurfaceCreated: () -> Unit = {}
    var onSurfaceDestroyed: () -> Unit = {}
    var onFocus: (Boolean) -> Unit = {}
    var onTap: () -> Unit = {}
    var strokeInProgress: () -> Boolean = { false }

    /**
     * Dismiss mode: an overlay (strip, popup or side panel) is open, so raw drawing is held off and any pen or
     * finger down on the board calls [onDismiss] and is swallowed up to its lift (see [onGesture]).
     */
    var dismissMode = false
    var onDismiss: () -> Unit = {}

    /**
     * Called with true when a swallowed gesture starts and with false when it ends (its lift or cancel, or the
     * surface going away under it).
     */
    var onSwallow: (Boolean) -> Unit = {}

    /** Overlay rects (view pixels) a window-callback down must miss to count as a down on the board. */
    internal var overlayRects: List<Rect> = emptyList()

    /** True from a dismissing down to its lift: [startInput] then leaves raw drawing as it is (no reopen under the pen). */
    internal var swallowing = false
        private set

    /** True when a stroke was already live at the swallowing down: its commit and erase events still pass. */
    private var swallowedLive = false

    /**
     * Down point (view pixels) of the last swallowed gesture, kept past its lift: the SDK delivers the raw stroke
     * at or after pen-up, so its commit can arrive once the swallow has ended. Cleared by the next unswallowed down.
     */
    private var swallowedAt: PointF? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tap = TapDetector(slop)

    /** True when [ev] belongs to a swallowed gesture; a commit consumes the remembered down point. */
    internal fun dropsEvent(ev: StrokeEvent): Boolean =
        when (ev) {
            is StrokeEvent.Committed -> ev.stroke.points.isNotEmpty() && dropsStroke(ev.stroke.points[0].x, ev.stroke.points[0].y, true)
            is StrokeEvent.Erase -> dropsStroke(ev.x, ev.y, false)
            StrokeEvent.Cancelled -> false
        }

    private fun dropsStroke(
        x: Float,
        y: Float,
        commit: Boolean,
    ): Boolean {
        if (swallowing) return !swallowedLive
        val at = swallowedAt ?: return false
        val hit = hypot(x - at.x, y - at.y) <= slop
        if (hit && commit) swallowedAt = null
        return hit
    }

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
     * The notebook [textLayer] (page-canvas pixels, transparent, see [TextLayer]) is drawn over the grid and the
     * background and under the ink; another instance redraws the page without a full refresh.
     */
    @Suppress("LongParameterList")
    fun show(
        page: PageCanvas,
        strokes: List<Stroke>,
        index: Int,
        preview: Stroke? = null,
        background: Bitmap? = null,
        textLayer: Bitmap? = null,
    ) {
        val sameBackground = background === this.background
        // Same layers: the document background and the text layer are the shown instances.
        val sameLayers = sameBackground && textLayer === this.textLayer
        val samePage = sameLayers && page == this.page && strokes == this.strokes && index == shownIndex
        if (samePage && preview == this.preview) return
        val appended = sameLayers && onlyAppended(page, strokes, index, preview)
        if (appended && rawLive()) return defer(strokes)
        syncCommitted()
        val full = needsFullRefresh(shownIndex, this.strokes, index, strokes) || (shownIndex != null && !sameBackground)
        val dragging = preview != null && samePage
        this.background = background
        this.textLayer = textLayer
        this.page = page
        this.strokes = strokes
        shownIndex = index
        this.preview = preview
        dragMode(dragging)
        redraw()
        if (full) fullRefresh.request()
    }

    /**
     * A new text layer and the strokes it leaves visible, drawn now (the caller holds raw drawing off, see
     * [TextLayerHolds]): committed ink is synced, then [dirtyPage] (page px, null for all) is drawn and refreshed in GU.
     * The next [show] with the same strokes and layer is then a no-op.
     */
    override fun swapText(
        strokes: List<Stroke>,
        textLayer: Bitmap?,
        dirtyPage: RectF?,
    ) {
        syncCommitted()
        this.strokes = strokes
        this.textLayer = textLayer
        if (!ready) return
        val dirty = dirtyPage?.let { viewRect(it) }
        if (dirty == null) {
            draw()
        } else {
            val canvas = dirtyCanvasSource(holder, dirty) ?: return
            try {
                canvas.clipRect(dirty)
                paint(canvas, RectF(dirty))
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        partialRefresh(this)
    }

    /** The GU refresh after a text swap; tests record it. */
    internal var partialRefresh: (View) -> Unit = Epd::partialRefresh

    /** [page] (page px) in view pixels, rounded out. */
    private fun viewRect(page: RectF): Rect {
        val g = geometry()
        val r = RectF(page.left * g.scale + g.dx, page.top * g.scale + g.dy, page.right * g.scale + g.dx, page.bottom * g.scale + g.dy)
        return Rect().also { r.roundOut(it) }
    }

    /** Takes [strokes] (the shown ones plus appended ones) without drawing; [syncCommitted] draws their rect later. */
    private fun defer(strokes: List<Stroke>) {
        val added = strokes.subList(this.strokes.size, strokes.size)
        this.strokes = strokes
        // Without a surface nothing is pending: surfaceCreated draws the whole page.
        if (!ready) return
        val g = geometry()
        val dirty = pendingDirty ?: Rect()
        added.forEach { s -> g.inkBounds(s)?.let { dirty.union(Rect().apply { it.roundOut(this) }) } }
        pendingDirty = dirty.takeIf { !it.isEmpty }
    }

    /**
     * Draws the dirty rect of the deferred strokes, with raw drawing left as it is: only what lies
     * in the rect (as widened by the lock) is drawn. Does nothing when nothing is pending.
     */
    override fun syncCommitted() {
        val dirty = pendingDirty ?: return
        pendingDirty = null
        if (!ready) return
        val canvas = dirtyCanvasSource(holder, dirty) ?: return
        try {
            canvas.clipRect(dirty)
            paint(canvas, RectF(dirty))
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
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
        if (stroke == null) return normalMode()
        handwritingMode()
        redraw()
    }

    /**
     * The handwriting mode while [dragging]; otherwise the drag or touch stroke has ended and this
     * frame shows the result in full quality. A touch stroke still in progress (quick writing
     * commits the previous one under it) keeps the fast mode.
     */
    private fun dragMode(dragging: Boolean) {
        when {
            dragging -> handwritingMode()
            live == null -> normalMode()
        }
    }

    private fun handwritingMode() {
        if (handwriting) return
        handwriting = true
        einkMode(true)
    }

    /** Ends the handwriting mode set by a drag or touch stroke; the next frame is drawn in the view's normal mode. */
    private fun normalMode() {
        if (!handwriting) return
        handwriting = false
        einkMode(false)
    }

    /**
     * [raw] (view pixels, as the input delivered it) in page pixels. Its geometry is logged under
     * [InkDiag] ([strokeDiagLine]) with the firmware's stroke parameters of its style.
     */
    fun committed(raw: Stroke): Stroke {
        val g = geometry()
        val committed = g.toCanvas(raw)
        val params = Epd.strokeParameters(BrushMapping.style(raw.tool))
        InkDiag.info(strokeDiagLine(raw, g.inkBounds(committed), width, height, screenOrigin(), params))
        return committed
    }

    /** Where this view's (0, 0) sits on the screen. */
    internal fun screenOrigin(): PointF {
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        return PointF(loc[0].toFloat(), loc[1].toFloat())
    }

    private fun redraw() {
        if (!ready) return
        refresher { draw() }
    }

    private fun draw() {
        val canvas = canvasSource(holder) ?: return
        pendingDirty = null
        try {
            paint(canvas, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** The whole board onto [canvas]; with [within] (view pixels) freehand strokes outside it are skipped. */
    private fun paint(
        canvas: AndroidCanvas,
        within: RectF?,
    ) {
        canvas.drawColor(Color.WHITE)
        val g = geometry()
        // The grid is view-only (never exported); it sits under the document background and the ink.
        grid.draw(canvas, g, page, within)
        background?.let {
            canvas.drawBitmap(it, pageMatrix(it, g), null)
        }
        textLayer?.let { canvas.drawBitmap(it, pageMatrix(it, g), null) }
        val shown =
            if (within == null) strokes else strokes.filter { s -> g.inkBounds(s)?.let { RectF.intersects(it, within) } ?: false }
        renderer.draw(canvas, shown, g.scale, g.dx, g.dy)
        // Redrawn on every drag or pen move event: never the vendor pens.
        preview?.let { renderer.drawPreview(canvas, listOf(it), g.scale, g.dx, g.dy) }
        live?.let { renderer.drawPreview(canvas, listOf(it), 1f, 0f, 0f) }
    }

    /** Maps a page-sized bitmap onto the view: the page's scale and offset. */
    private fun pageMatrix(
        b: Bitmap,
        g: BoardGeometry,
    ) = Matrix().apply {
        postScale(g.scale * page.w / b.width, g.scale * page.h / b.height)
        postTranslate(g.dx, g.dy)
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
        // No lift reaches a view without a surface: the swallowed gesture ends here, so its hold cannot leak.
        if (swallowing) endSwallow()
        onSurfaceDestroyed()
    }

    // Before super: the inputs replace the view's touch listener (shape drags, touch fallback) and may consume the event.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (onGesture(event.actionMasked, event.x, event.y)) return true
        if (tap.onEvent(event, strokeInProgress())) onTap()
        return super.dispatchTouchEvent(event)
    }

    /**
     * A touch event from the window callback (it sees events before any view, also while raw drawing owns the pen),
     * in window pixels. A down on the board outside the overlays starts the dismiss; the lift ends it even when the
     * view itself never gets the events.
     */
    fun onWindowTouch(event: MotionEvent) {
        val loc = IntArray(2).also { getLocationInWindow(it) }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = (event.x - loc[0]).toInt()
            val y = (event.y - loc[1]).toInt()
            if (x !in 0 until width || y !in 0 until height || overlayRects.any { it.contains(x, y) }) return
        }
        onGesture(event.actionMasked, event.x - loc[0], event.y - loc[1])
    }

    /**
     * Dismiss mode state machine, fed by the view and the window callback (idempotent across both copies of an
     * event). A down in [dismissMode] calls [onDismiss] and starts swallowing; the gesture ends at its up or cancel.
     * True while the event belongs to a swallowed gesture, so the caller consumes it: no ink, no stroke.
     */
    private fun onGesture(
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> if (!swallowing) startGesture(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swallowing) return endSwallow()
        }
        return swallowing
    }

    private fun startGesture(
        x: Float,
        y: Float,
    ) {
        swallowedAt = null
        if (dismissMode) startSwallow(x, y)
    }

    private fun startSwallow(
        x: Float,
        y: Float,
    ) {
        swallowing = true
        swallowedLive = strokeInProgress()
        swallowedAt = if (swallowedLive) null else PointF(x, y)
        onSwallow(true)
        onDismiss()
    }

    private fun endSwallow(): Boolean {
        swallowing = false
        onSwallow(false)
        return true
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
 * paused while the activity is paused or the window has no focus. [onBoardTap] fires for a finger
 * tap on the board (never a stylus, never while [StrokeInput.strokeInProgress]).
 */
@Suppress("LongParameterList", "LongMethod") // the view wiring is one block of callbacks
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
    textLayer: Bitmap? = null,
    onSurface: (TextLayerSurface) -> Unit = {},
    onPenActivity: () -> Unit = {},
    onBoardTap: () -> Unit = {},
    dismissMode: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val surface = remember { arrayOfNulls<BoardSurfaceView>(1) }
    val strokeCb by rememberUpdatedState(onStroke)
    val eraseCb by rememberUpdatedState(onErase)
    val shapeDragCb by rememberUpdatedState(onShapeDrag)
    val shapeCancelCb by rememberUpdatedState(onShapeCancel)
    val tapCb by rememberUpdatedState(onBoardTap)
    val dismissCb by rememberUpdatedState(onDismiss)
    val penActivity by rememberUpdatedState(onPenActivity)
    val excludeNow by rememberUpdatedState(exclude)
    // Raw drawing stays held off for the whole swallowed gesture, even after the overlays are gone.
    var swallowing by remember { mutableStateOf(false) }
    val held = dismissMode || swallowing
    val gate = remember(input) { InputGate(input) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { gate.setResumed(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { gate.setResumed(true) }

    fun startOn(v: BoardSurfaceView) = v.startInput(input, excludeNow)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BoardSurfaceView(ctx).also { v ->
                surface[0] = v
                onSurface(v)
                v.refresher = input::refresh
                v.rawLive = { input.rawLive }
                v.onSurfaceCreated = { startOn(v) }
                v.onSurfaceDestroyed = { input.stop() }
                v.onFocus = gate::setFocused
                v.onTap = { tapCb() }
                v.onDismiss = { dismissCb() }
                v.onSwallow = { on -> swallowing = on }
                v.strokeInProgress = { input.strokeInProgress }
                v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startOn(v) }
            }
        },
        update = {
            it.dismissMode = dismissMode
            // Consumed strokes (transcribed into text runs) are never drawn.
            it.show(notebook.canvas, shownStrokes(notebook), notebook.current, preview, background, textLayer)
        },
        onRelease = { input.stop() },
    )
    // Rects that changed under the pen take effect after the lift, once the hold of the swallowed gesture is released.
    LaunchedEffect(exclude, swallowing) { if (!swallowing) surface[0]?.let { startOn(it) } }
    // Dismiss mode and a swallowed gesture keep raw drawing off through the input's hold, as every overlay change does.
    HoldWhile(input, held)
    DisposableEffect(input) {
        input.onWindowTouch = { ev -> surface[0]?.onWindowTouch(ev) }
        onDispose { input.onWindowTouch = null }
    }
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
            penActivity()
            if (ev is StrokeEvent.Cancelled) return@collect shapeCancelCb()
            // A stroke the firmware still commits under a swallowed gesture is dropped (a safety net: opens are paused).
            val v = surface[0]?.takeUnless { it.dropsEvent(ev) } ?: return@collect
            val g = v.geometry()
            when (ev) {
                is StrokeEvent.Committed -> strokeCb(v.committed(ev.stroke))
                is StrokeEvent.Erase -> eraseCb((ev.x - g.dx) / g.scale, (ev.y - g.dy) / g.scale, ev.radius / g.scale)
                StrokeEvent.Cancelled -> Unit
            }
        }
    }
}

/**
 * One [StrokeInput.holdUi] while [held], released exactly once when [held] ends, [input] changes or the
 * caller leaves the composition.
 */
@Composable
private fun HoldWhile(
    input: StrokeInput,
    held: Boolean,
) {
    DisposableEffect(input, held) {
        if (held) input.holdUi()
        onDispose { if (held) input.releaseUi() }
    }
}

/**
 * (Re)starts [input] on this view with [exclude] window rects. Does nothing while the surface does
 * not exist (a layout or exclude change after surfaceDestroyed must not reopen raw drawing) or the
 * view has no size; surfaceCreated calls it again. Nor during a swallowed gesture: a reopen would run
 * with the pen on the board, so the exclude effect starts the input after the lift instead.
 */
internal fun BoardSurfaceView.startInput(
    input: StrokeInput,
    exclude: List<Rect>,
) {
    if (!surfaceReady || swallowing) return
    if (width <= 0 || height <= 0) return
    val loc = IntArray(2).also { getLocationInWindow(it) }
    val overlays = excludeRectsInView(loc[0], loc[1], width, height, exclude)
    overlayRects = overlays
    input.start(this, Rect(0, 0, width, height), overlays)
}

/** Non-surface rendering of a page (text layer, then visible ink) for previews and screenshot tests, where a SurfaceView does not draw. */
@Composable
fun BoardPreviewCanvas(
    canvas: PageCanvas,
    page: Page,
    modifier: Modifier = Modifier,
    renderer: StrokeRenderer = CanvasStrokeRenderer(),
    textLayer: Bitmap? = null,
) {
    Canvas(modifier.background(androidx.compose.ui.graphics.Color.White)) {
        val g = BoardGeometry(size.width.toInt(), size.height.toInt(), canvas)
        drawIntoCanvas {
            textLayer?.let { b ->
                val m =
                    Matrix().apply {
                        postScale(g.scale * canvas.w / b.width, g.scale * canvas.h / b.height)
                        postTranslate(g.dx, g.dy)
                    }
                it.nativeCanvas.drawBitmap(b, m, null)
            }
            renderer.draw(it.nativeCanvas, page.visibleStrokes, g.scale, g.dx, g.dy)
        }
    }
}
