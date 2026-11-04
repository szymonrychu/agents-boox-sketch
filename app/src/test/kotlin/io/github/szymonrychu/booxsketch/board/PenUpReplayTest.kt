package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.data.TouchPointList
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputSettings
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.model.Tool
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.concurrent.Executor

/** Raw drawing and surface frames in one ordered log, as the panel would see them. */
private class LoggingRawDrawing(
    private val log: MutableList<String>,
) : RawDrawing {
    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun open() = Unit

    override fun setEnabled(enabled: Boolean) {
        log += "raw:$enabled"
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) = Unit

    override fun close() = Unit
}

private fun View.findSurface(): BoardSurfaceView =
    when (this) {
        is BoardSurfaceView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOf { runCatching { getChildAt(it).findSurface() }.getOrNull() }
        else -> error("no BoardSurfaceView")
    }

/**
 * Replays a pen stroke as the firmware reports it into the real board with the real Onyx input
 * (only the vendor TouchHelper is faked). The firmware overlay shows the fresh ink while raw
 * drawing is on, so a committed stroke must not touch the surface at all (a frame posted while raw
 * drawing is on stalls the next stroke's ink): the surface catches up on the dirty rect only, when
 * the pen goes idle or right before raw drawing turns off. Removals and page changes still go
 * through the disable, draw, enable bracket, after that flush, so no committed stroke vanishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PenUpReplayTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val log = mutableListOf<String>()
    private val dirty = mutableListOf<Rect>()
    private lateinit var surface: BoardSurfaceView
    private var callback: RawInputCallback? = null
    private lateinit var vm: BoardViewModel
    private lateinit var input: StrokeInput

    @Before
    fun setUp() {
        InputSelector.resetForTest()
        vm = BoardViewModel(NotebookStore(File(tmp.newFolder(), "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val onyx =
            OnyxStrokeInput(
                ApplicationProvider.getApplicationContext(),
                newId = { "pen${log.size}" },
                rawDrawing = { _, cb ->
                    callback = cb
                    LoggingRawDrawing(log)
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
                diagnostics = {},
                scope = CoroutineScope(Dispatchers.Unconfined),
                onyxFactory = { onyx },
                touchFactory = { TouchStrokeInput(newId = { "touch" }) },
                mainExecutor = Executor { it.run() },
            ).select()
    }

    private fun board() {
        var host: View? = null
        compose.setContent {
            host = LocalView.current
            BoardScreen(vm, input)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val surface = checkNotNull(host).findSurface()
            surface.canvasSource = {
                log += "draw"
                Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            }
            surface.dirtyCanvasSource = { _, r ->
                log += "dirty"
                dirty += Rect(r)
                Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            }
            this.surface = surface
            surface.surfaceCreated(surface.holder)
        }
        settle()
        log.clear()
        dirty.clear()
    }

    /** Lets a debounced full refresh (load, clear, page switch) fire now, so it cannot land inside a later step. */
    private fun settle() {
        compose.waitForIdle()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BoardSurfaceView.FULL_REFRESH_DELAY_MS)) }
        compose.waitForIdle()
    }

    private fun penStroke(vararg xs: Float) {
        val list = TouchPointList().apply { xs.forEach { add(TouchPoint(it, it, 2048f, 1f, 0, 0, 0L)) } }
        compose.runOnIdle {
            callback!!.onBeginRawDrawing(false, TouchPoint(xs[0], xs[0], 2048f, 1f, 0, 0, 0L))
            xs.forEach { callback!!.onRawDrawingTouchPointMoveReceived(TouchPoint(it, it, 2048f, 1f, 0, 0, 0L)) }
            callback!!.onEndRawDrawing(false, TouchPoint(xs.last(), xs.last(), 2048f, 1f, 0, 0, 0L))
            callback!!.onRawDrawingTouchPointListReceived(list)
        }
        compose.waitForIdle()
    }

    private fun strokes() =
        vm.notebook.value.pages[0]
            .strokes

    private fun penIdle() {
        compose.runOnIdle { callback!!.onPenUpRefresh(RectF(0f, 0f, 1f, 1f)) }
        compose.waitForIdle()
    }

    /** View pixels the replayed points cover; every stroke point lies on the diagonal x == y. */
    private fun Rect.coversDiagonal(vararg xs: Float) = xs.all { contains(it.toInt(), it.toInt()) }

    @Test
    fun quick_pen_strokes_never_touch_the_surface_or_raw_drawing_while_raw_drawing_is_on() {
        board()
        penStroke(10f, 20f, 30f)
        penStroke(40f, 50f, 60f)
        penStroke(70f, 80f, 90f)
        assertThat(strokes()).hasSize(3)
        assertThat(log).doesNotContain("draw")
        assertThat(log).doesNotContain("dirty")
        assertThat(log).doesNotContain("raw:false")
    }

    @Test
    fun pen_idle_redraws_only_the_dirty_rect_of_the_strokes_since_the_last_sync_once() {
        board()
        penStroke(10f, 20f, 30f)
        penStroke(40f, 50f, 60f)
        penStroke(70f, 80f, 90f)
        penIdle()
        assertThat(log).containsExactly("dirty")
        val r = dirty.single()
        assertThat(r.coversDiagonal(10f, 30f, 40f, 60f, 70f, 90f)).isTrue()
        assertThat(r.width()).isLessThan(surface.width)
        penIdle()
        assertThat(log).containsExactly("dirty")
    }

    @Test
    fun a_new_stroke_before_the_idle_callback_defers_the_sync_to_the_next_idle() {
        board()
        penStroke(10f, 20f, 30f)
        compose.runOnIdle { callback!!.onBeginRawDrawing(false, TouchPoint(40f, 40f, 2048f, 1f, 0, 0, 0L)) }
        penIdle()
        assertThat(log).isEmpty()
        penStroke(40f, 50f, 60f)
        penIdle()
        assertThat(log).containsExactly("dirty")
        assertThat(dirty.single().coversDiagonal(10f, 30f, 40f, 60f)).isTrue()
    }

    private fun assertFlushedThenBracketed() {
        assertThat(log.first()).isEqualTo("dirty")
        assertThat(log.indexOf("dirty")).isLessThan(log.indexOf("raw:false"))
        assertThat(dirty.first().coversDiagonal(10f, 30f)).isTrue()
    }

    @Test
    fun erase_clear_and_page_switch_flush_before_raw_drawing_turns_off() {
        board()
        penStroke(10f, 20f, 30f)
        val p = strokes().first().points.last()
        compose.runOnIdle { vm.onErase(p.x, p.y, 1f) }
        compose.waitForIdle()
        assertFlushedThenBracketed()
        settle()
        for (action in listOf<() -> Unit>({ vm.clear() }, { vm.addPage() })) {
            log.clear()
            dirty.clear()
            penStroke(10f, 20f, 30f)
            compose.runOnIdle { action() }
            compose.waitForIdle()
            assertFlushedThenBracketed()
            settle()
        }
    }

    @Test
    fun hold_pause_shape_tool_and_stop_flush_before_raw_drawing_turns_off() {
        board()
        val actions =
            listOf<() -> Unit>(
                { input.holdUi() },
                { input.pause() },
                { input.setTool(Tool.RECTANGLE, 4f) },
                { input.stop() },
            )
        val undo = listOf<() -> Unit>({ input.releaseUi() }, { input.resume() }, { input.setTool(Tool.BALLPOINT, 4f) }, {})
        actions.zip(undo).forEach { (action, back) ->
            log.clear()
            dirty.clear()
            penStroke(10f, 20f, 30f)
            compose.runOnIdle { action() }
            compose.waitForIdle()
            assertThat(log.first()).isEqualTo("dirty")
            if ("raw:false" in log) assertThat(log.indexOf("dirty")).isLessThan(log.indexOf("raw:false"))
            assertThat(dirty.first().coversDiagonal(10f, 30f)).isTrue()
            compose.runOnIdle { back() }
            compose.waitForIdle()
        }
    }

    @Test
    fun undo_and_erase_still_turn_raw_drawing_off_around_the_redraw() {
        board()
        penStroke(10f, 20f, 30f)
        penStroke(40f, 50f, 60f)
        log.clear()
        compose.runOnIdle { vm.undo() }
        compose.waitForIdle()
        assertThat(strokes()).hasSize(1)
        assertThat(log.take(4)).containsExactly("dirty", "raw:false", "draw", "raw:true").inOrder()
        log.clear()
        compose.runOnIdle { vm.redo() }
        compose.waitForIdle()
        log.clear()
        val s = strokes().first().points.first()
        compose.runOnIdle { vm.onErase(s.x, s.y, 50f) }
        compose.waitForIdle()
        assertThat(log.take(4)).containsExactly("dirty", "raw:false", "draw", "raw:true").inOrder()
    }
}
