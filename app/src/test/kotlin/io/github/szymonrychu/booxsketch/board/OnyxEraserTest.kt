package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.data.TouchPointList
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputSettings
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.onyx.OnyxStrokeInput
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import io.github.szymonrychu.booxsketch.onyx.RawDrawing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/** The SDK side of raw drawing: whether the firmware would draw raw ink now. */
private class SwitchRawDrawing : RawDrawing {
    var on = false

    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun open() {
        on = false
    }

    override fun setEnabled(enabled: Boolean) {
        on = enabled
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) = Unit

    override fun close() {
        on = false
    }
}

private fun View.findSurface(): BoardSurfaceView =
    when (this) {
        is BoardSurfaceView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOf { runCatching { getChildAt(it).findSurface() }.getOrNull() }
        else -> error("no BoardSurfaceView")
    }

/**
 * The eraser on the device path: the real board and strip with the real Onyx input under the
 * fallback wrapper (only the vendor TouchHelper is faked). The pen tip, not only the stylus eraser
 * end, must erase once the strip's eraser is selected: raw drawing goes off (the firmware would
 * ink a freehand stroke otherwise) and the pen's events erase the strokes they touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxEraserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val tmp = TemporaryFolder()

    private val raw = SwitchRawDrawing()
    private val diag = mutableListOf<String>()
    private var callback: RawInputCallback? = null
    private lateinit var vm: BoardViewModel
    private lateinit var input: StrokeInput
    private var host: View? = null

    @Before
    fun setUp() {
        InputSelector.resetForTest()
        vm = BoardViewModel(NotebookStore(File(tmp.newFolder(), "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val onyx =
            OnyxStrokeInput(
                compose.activity,
                newId = { "pen" },
                rawDrawing = { _, cb ->
                    callback = cb
                    raw
                },
                maxPressure = { 4096f },
                afterNextDraw = { _, r -> r.run() },
                fullRefresh = {},
            )
        input =
            InputSelector(
                probe = { ProbeResult(true, "ok") },
                settings =
                    object : InputSettings {
                        override val forceTouch = false
                    },
                diagnostics = { diag += it },
                scope = CoroutineScope(Dispatchers.Unconfined),
                onyxFactory = { onyx },
                touchFactory = { TouchStrokeInput(newId = { "touch" }) },
                mainExecutor = Executor { it.run() },
            ).select()
        compose.setContent {
            host = LocalView.current
            BoardScreen(vm, input)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val surface = checkNotNull(host).findSurface()
            surface.canvasSource = { Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) }
            surface.dirtyCanvasSource = { _, _ -> Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) }
            surface.surfaceCreated(surface.holder)
        }
        compose.waitForIdle()
    }

    /** A pen stroke as the firmware reports it while raw drawing is on, on the diagonal x == y (view pixels). */
    private fun rawPenStroke(vararg xs: Float) {
        val list = TouchPointList().apply { xs.forEach { add(TouchPoint(it, it, 2048f, 1f, 0, 0, 0L)) } }
        compose.runOnIdle {
            callback!!.onBeginRawDrawing(false, TouchPoint(xs[0], xs[0], 2048f, 1f, 0, 0, 0L))
            callback!!.onEndRawDrawing(false, TouchPoint(xs.last(), xs.last(), 2048f, 1f, 0, 0, 0L))
            callback!!.onRawDrawingTouchPointListReceived(list)
        }
        compose.waitForIdle()
    }

    private fun stylus(
        action: Int,
        x: Float,
        y: Float,
        down: Long,
    ): MotionEvent {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                },
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 0.5f
                },
            )
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    /** A pen-tip drag as the window receives it (the window callback sees it before any view, also in raw mode). */
    private fun penTipDrag(vararg xs: Float) {
        val board = IntArray(2)
        compose.runOnIdle { compose.activity.findViewById<View>(android.R.id.content).getLocationInWindow(board) }
        val down = SystemClock.uptimeMillis()
        val actions = listOf(MotionEvent.ACTION_DOWN) + List(xs.size - 2) { MotionEvent.ACTION_MOVE } + MotionEvent.ACTION_UP
        compose.runOnIdle {
            actions.zip(xs.toList()).forEach { (a, x) ->
                compose.activity.window.callback
                    .dispatchTouchEvent(stylus(a, x + board[0], x + board[1], down))
            }
        }
        compose.waitForIdle()
    }

    /** A strip tap, settled: the harness draws (and so releases the refresher's hold) only on a layout request. */
    private fun tap(name: String) {
        compose.onNodeWithTag(stripTag(name)).performClick()
        compose.waitForIdle()
        compose.runOnUiThread { host!!.rootView.requestLayout() }
        compose.waitForIdle()
    }

    private fun strokes() =
        vm.notebook.value.pages[0]
            .strokes

    @Test
    fun strip_eraser_turns_raw_ink_off_and_the_pen_tip_erases_the_stroke_it_touches() {
        assertThat(raw.on).isTrue()
        rawPenStroke(150f, 200f, 250f)
        assertThat(strokes()).hasSize(1)

        tap("eraser")
        assertThat(vm.eraser.value).isTrue()
        // Raw drawing on would let the firmware ink a freehand stroke under the pen instead of erasing.
        assertThat(raw.on).isFalse()

        penTipDrag(190f, 200f, 210f)
        assertThat(strokes()).isEmpty()
        assertThat(diag).isEmpty()
    }

    @Test
    fun picking_the_pen_after_the_eraser_draws_raw_ink_again() {
        tap("eraser")
        assertThat(raw.on).isFalse()

        tap("pen")
        assertThat(vm.eraser.value).isFalse()
        assertThat(raw.on).isTrue()
        // A pen-tip drag no longer erases or draws through touch: raw drawing owns the pen again.
        rawPenStroke(150f, 200f, 250f)
        penTipDrag(190f, 200f, 210f)
        assertThat(strokes()).hasSize(1)
        assertThat(diag).isEmpty()
    }
}
