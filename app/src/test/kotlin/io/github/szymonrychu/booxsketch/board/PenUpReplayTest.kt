package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
 * (only the vendor TouchHelper is faked). The firmware overlay shows the fresh ink until raw
 * drawing is turned off, so a committed stroke must reach the surface without a disable in
 * between; removals and page changes still go through the disable, draw, enable bracket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PenUpReplayTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val log = mutableListOf<String>()
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
            surface.surfaceCreated(surface.holder)
        }
        compose.waitForIdle()
        log.clear()
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

    @Test
    fun a_committed_pen_stroke_reaches_the_surface_without_turning_raw_drawing_off() {
        board()
        penStroke(10f, 20f, 30f)
        assertThat(strokes()).hasSize(1)
        assertThat(log).contains("draw")
        assertThat(log).doesNotContain("raw:false")
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
        assertThat(log.take(3)).containsExactly("raw:false", "draw", "raw:true").inOrder()
        log.clear()
        compose.runOnIdle { vm.redo() }
        compose.waitForIdle()
        log.clear()
        val s = strokes().first().points.first()
        compose.runOnIdle { vm.onErase(s.x, s.y, 50f) }
        compose.waitForIdle()
        assertThat(log.take(3)).containsExactly("raw:false", "draw", "raw:true").inOrder()
    }
}
