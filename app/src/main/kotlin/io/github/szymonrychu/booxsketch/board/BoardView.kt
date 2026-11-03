package io.github.szymonrychu.booxsketch.board

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
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

/** Limit rect of [v] in view pixels, excluding system bar and display cutout areas of its window. */
internal fun safeLimit(v: View): Rect {
    val loc = IntArray(2)
    v.getLocationInWindow(loc)
    val root = v.rootView
    val i =
        ViewCompat.getRootWindowInsets(v)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?: Insets.NONE
    return BoardLimit.compute(
        Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height),
        Rect(0, 0, root.width, root.height),
        Rect(i.left, i.top, i.right, i.bottom),
    )
}

/** Surface the page strokes are drawn on, behind the toolbar. */
class BoardSurfaceView(
    context: Context,
    private val renderer: StrokeRenderer = CanvasStrokeRenderer(),
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private var strokes: List<Stroke> = emptyList()
    private var page: PageCanvas = PageCanvas(1, 1, 1f)
    private var ready = false

    init {
        holder.addCallback(this)
    }

    fun geometry() = BoardGeometry(width, height, page)

    fun show(
        page: PageCanvas,
        strokes: List<Stroke>,
    ) {
        this.page = page
        this.strokes = strokes
        redraw()
    }

    private fun redraw() {
        if (!ready) return
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
    }
}

/**
 * The drawing board: a [SurfaceView] in an AndroidView showing [pageContent], fed by [input].
 * Stroke and erase events are mapped to page-canvas pixels before reaching the callbacks.
 */
@Composable
fun BoardView(
    canvas: PageCanvas,
    page: Page,
    input: StrokeInput,
    onStroke: (Stroke) -> Unit,
    onErase: (x: Float, y: Float, radius: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var surface: BoardSurfaceView? = null
    val strokeCb by rememberUpdatedState(onStroke)
    val eraseCb by rememberUpdatedState(onErase)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BoardSurfaceView(ctx).also { v ->
                surface = v
                v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> input.start(v, safeLimit(v), emptyList()) }
            }
        },
        update = { it.show(canvas, page.strokes) },
        onRelease = { input.stop() },
    )
    LaunchedEffect(input) {
        input.events.collect { ev ->
            val g = surface?.geometry() ?: return@collect
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
