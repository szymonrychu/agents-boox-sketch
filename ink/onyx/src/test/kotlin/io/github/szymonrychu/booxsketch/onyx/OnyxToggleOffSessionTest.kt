package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private class SessionRawDrawing(
    private val log: MutableList<String>,
) : RawDrawing {
    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) {
        log += "limit"
    }

    override fun open() {
        log += "open"
    }

    override fun setEnabled(enabled: Boolean) {
        log += "enabled:$enabled"
    }

    override fun setPenUpRefreshTimeMs(ms: Int) {
        log += "idle:$ms"
    }

    override fun forceSetEnabled(enabled: Boolean) {
        log += "force:$enabled"
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) {
        log += "brush:$style:$width:${Integer.toHexString(color)}"
    }

    override fun close() {
        log += "close"
    }

    override fun clearRegionPenConfig() {
        log += "clearRegion"
    }
}

/** The whole v0.19.2 session call log, now behind the one-time region config clear, and the pen-down diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxToggleOffSessionTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val view = View(ctx)
    private val log = mutableListOf<String>()
    private val draws = ArrayDeque<Runnable>()
    private var cb: RawInputCallback? = null
    private val input =
        OnyxStrokeInput(
            ctx,
            rawDrawing = { _, c ->
                cb = c
                SessionRawDrawing(log)
            },
            maxPressure = { 4096f },
            afterNextDraw = { _, r -> draws.addLast(r) },
            fullRefresh = { log += "full" },
        )
    private val point = TouchPoint(1f, 1f, 2048f, 1f, 0, 0, 0L)
    private val limit = Rect(0, 0, 100, 100)

    private fun started() {
        input.start(view, limit, emptyList())
        draws.removeFirst().run()
    }

    private fun brushOf(
        tool: Tool,
        color: PenColor,
    ) = "brush:${BrushMapping.style(tool)}:${BrushMapping.width(tool, 4f)}:${Integer.toHexString(color.argb)}"

    private fun session(): List<String> {
        started()
        input.setTool(Tool.FOUNTAIN, 4f)
        input.setColor(PenColor.BLUE)
        input.holdUi()
        input.start(view, limit, listOf(Rect(0, 0, 10, 10)))
        input.releaseUi()
        input.refresh { log += "redraw" }
        input.pause()
        input.resume()
        cb!!.onBeginRawDrawing(false, point)
        cb!!.onEndRawDrawing(false, point)
        cb!!.onPenUpRefresh(RectF())
        shadowOf(Looper.getMainLooper()).idle()
        input.setEraser(true)
        input.setEraser(false)
        input.stop()
        return log.toList()
    }

    /** The v0.18.0 call sequence plus the vendor demo's forced pause after every open (T-78, the tap-to-dismiss regression). */
    @Test
    fun the_default_toggles_off_sequence_pauses_after_every_open() {
        val ballpoint = brushOf(Tool.BALLPOINT, PenColor.BLACK)
        val fountain = brushOf(Tool.FOUNTAIN, PenColor.BLACK)
        val fountainBlue = brushOf(Tool.FOUNTAIN, PenColor.BLUE)
        assertThat(session())
            .containsExactly(
                "clearRegion",
                "limit",
                "open",
                "force:false",
                "idle:${OnyxStrokeInput.PEN_IDLE_MS}",
                ballpoint,
                "enabled:false",
                "full",
                "enabled:true",
                ballpoint,
                fountain,
                fountainBlue,
                "enabled:false",
                "limit",
                "open",
                "force:false",
                "idle:${OnyxStrokeInput.PEN_IDLE_MS}",
                fountainBlue,
                "enabled:false",
                "enabled:true",
                fountainBlue,
                "enabled:false",
                "redraw",
                "enabled:true",
                fountainBlue,
                "enabled:false",
                "enabled:true",
                fountainBlue,
                "enabled:false",
                "enabled:true",
                fountainBlue,
                "close",
            ).inOrder()
    }

    @Test
    fun every_pen_down_logs_the_shortcut_flag_and_the_stroke_ordinal_since_the_last_brush() {
        started()
        ShadowLog.clear()
        cb!!.onBeginRawDrawing(false, point)
        cb!!.onEndRawDrawing(false, point)
        cb!!.onBeginRawDrawing(true, point)
        cb!!.onEndRawDrawing(false, point)
        input.setColor(PenColor.BLUE)
        cb!!.onBeginRawDrawing(false, point)
        val lines = ShadowLog.getLogsForTag(InkDiag.TAG).map { it.msg }.filter { it.startsWith("pen down") }
        assertThat(lines)
            .containsExactly(
                "pen down shortcut=false stroke=1",
                "pen down shortcut=true stroke=2",
                "pen down shortcut=false stroke=1",
            ).inOrder()
    }
}
