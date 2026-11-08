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

/** Records every [RawDrawing] call, the new experiment members included, in order. */
private class RecordingRawDrawing(
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

    override fun setPenUpRefreshTimeMs(ms: Int) {
        log += "idle:$ms"
    }

    override fun setBrushRawDrawingEnabled(enabled: Boolean) {
        log += "brushRaw:$enabled"
    }

    override fun applyStrokeParameters(style: Int) {
        log += "params:$style"
    }

    override fun forceSetEnabled(enabled: Boolean) {
        log += "force:$enabled"
    }

    override fun setRenderEnabled(enabled: Boolean) {
        log += "render:$enabled"
    }

    override fun setRegionPenConfig(
        limit: Rect,
        configs: List<PenStrokeConfig>,
    ) {
        val each = configs.joinToString(",") { "${it.penButton}/${it.style}/${it.width}/${Integer.toHexString(it.color)}" }
        log += "region:${limit.toShortString()}:$each"
    }

    override fun clearRegionPenConfig() {
        log += "clearRegion"
    }
}

/**
 * The T-78 live ink experiments ([InkExperiments]). These pin the model of the calls, not the firmware: whether a
 * vendor order or a region config makes the live ink right is only answered on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxInkExperimentsTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val view = View(ctx)
    private val log = mutableListOf<String>()
    private val draws = ArrayDeque<Runnable>()
    private val delayed = ArrayDeque<Pair<Runnable, Long>>()
    private var toggles = InkExperiments.OFF
    private var color = false
    private var cb: RawInputCallback? = null
    private val input =
        OnyxStrokeInput(
            ctx,
            rawDrawing = { _, c ->
                cb = c
                RecordingRawDrawing(log)
            },
            maxPressure = { 4096f },
            afterNextDraw = { _, r -> draws.addLast(r) },
            fullRefresh = { log += "full" },
            experiments =
                InkExperimentEnv(
                    toggles = { toggles },
                    colorDevice = { color },
                    penButtons = { listOf("TIP", "SIDE_BUTTON") },
                    postDelayed = { r, ms -> delayed.addLast(r to ms) },
                ),
        )
    private val point = TouchPoint(1f, 1f, 2048f, 1f, 0, 0, 0L)
    private val ballpoint = brushOf(Tool.BALLPOINT, PenColor.BLACK)
    private val ballpointParams = "params:${BrushMapping.style(Tool.BALLPOINT)}"
    private val limit = Rect(0, 0, 100, 100)

    private fun started() {
        input.start(view, limit, emptyList())
        draws.removeFirst().run()
    }

    private fun brushOf(
        tool: Tool,
        color: PenColor,
    ) = "brush:${BrushMapping.style(tool)}:${BrushMapping.width(tool, 4f)}:${Integer.toHexString(color.argb)}"

    private fun region(
        tool: Tool,
        color: PenColor,
    ): String {
        val one = "${BrushMapping.style(tool)}/${BrushMapping.width(tool, 4f)}/${Integer.toHexString(color.argb)}"
        return "region:${limit.toShortString()}:TIP/$one,SIDE_BUTTON/$one"
    }

    /** Every member a session can see, run with both toggles off. */
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

    @Test
    fun both_toggles_off_reproduce_the_v0_18_0_call_sequence() {
        val fountain = brushOf(Tool.FOUNTAIN, PenColor.BLACK)
        val fountainBlue = brushOf(Tool.FOUNTAIN, PenColor.BLUE)
        assertThat(session())
            .containsExactly(
                "limit",
                "open",
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
        assertThat(delayed).isEmpty()
    }

    @Test
    fun vendor_order_applies_the_brush_before_the_transition_on_a_mono_device() {
        toggles = InkExperiments(vendorResumeOrder = true, regionPenConfig = false)
        input.start(view, limit, emptyList())
        log.clear()
        draws.removeFirst().run()
        assertThat(log)
            .containsExactly(
                "full",
                "brushRaw:true",
                ballpoint,
                ballpointParams,
                "enabled:true",
                "force:true",
            ).inOrder()
        assertThat(delayed).isEmpty()
    }

    @Test
    fun vendor_order_waits_500_ms_on_a_colour_device_and_drops_a_resume_held_again() {
        toggles = InkExperiments(vendorResumeOrder = true, regionPenConfig = false)
        color = true
        started()
        assertThat(delayed.single().second).isEqualTo(500L)
        log.clear()
        delayed.removeFirst().first.run()
        assertThat(log).containsExactly("brushRaw:true", ballpoint, ballpointParams, "enabled:true", "force:true").inOrder()
        input.holdUi()
        input.releaseUi()
        input.holdUi()
        log.clear()
        delayed.removeFirst().first.run()
        assertThat(log).isEmpty()
        input.releaseUi()
        input.stop()
        log.clear()
        delayed.removeFirst().first.run()
        assertThat(log).isEmpty()
    }

    @Test
    fun vendor_order_follows_a_live_tool_or_colour_change_with_a_render_off_on_pair() {
        toggles = InkExperiments(vendorResumeOrder = true, regionPenConfig = false)
        started()
        log.clear()
        input.setTool(Tool.FOUNTAIN, 4f)
        input.setColor(PenColor.BLUE)
        assertThat(log)
            .containsExactly(
                brushOf(Tool.FOUNTAIN, PenColor.BLACK),
                "render:false",
                "render:true",
                brushOf(Tool.FOUNTAIN, PenColor.BLUE),
                "render:false",
                "render:true",
            ).inOrder()
    }

    @Test
    fun vendor_order_never_turns_the_render_on_under_a_hold() {
        toggles = InkExperiments(vendorResumeOrder = true, regionPenConfig = false)
        started()
        input.holdUi()
        log.clear()
        input.setColor(PenColor.BLUE)
        assertThat(log).containsExactly(brushOf(Tool.BALLPOINT, PenColor.BLUE))
    }

    @Test
    fun region_config_carries_the_current_brush_and_limit_after_open_enable_and_brush_and_is_cleared_on_close() {
        toggles = InkExperiments(vendorResumeOrder = false, regionPenConfig = true)
        input.start(view, limit, emptyList())
        assertThat(log)
            .containsExactly(
                "limit",
                "open",
                "idle:${OnyxStrokeInput.PEN_IDLE_MS}",
                ballpoint,
                region(Tool.BALLPOINT, PenColor.BLACK),
                "enabled:false",
            ).inOrder()
        log.clear()
        draws.removeFirst().run()
        assertThat(log).containsExactly("full", "enabled:true", ballpoint, region(Tool.BALLPOINT, PenColor.BLACK)).inOrder()
        log.clear()
        input.setColor(PenColor.RED)
        input.setTool(Tool.FOUNTAIN, 4f)
        assertThat(log)
            .containsExactly(
                brushOf(Tool.BALLPOINT, PenColor.RED),
                region(Tool.BALLPOINT, PenColor.RED),
                brushOf(Tool.FOUNTAIN, PenColor.RED),
                region(Tool.FOUNTAIN, PenColor.RED),
            ).inOrder()
        log.clear()
        input.stop()
        assertThat(log).containsExactly("clearRegion", "close").inOrder()
    }

    @Test
    fun region_config_is_cleared_before_a_new_view_replaces_the_session() {
        toggles = InkExperiments(vendorResumeOrder = false, regionPenConfig = true)
        started()
        log.clear()
        input.start(View(ctx), limit, emptyList())
        assertThat(log.take(2)).containsExactly("clearRegion", "close").inOrder()
    }

    @Test
    fun both_toggles_together_write_the_region_config_after_the_vendor_transition() {
        toggles = InkExperiments()
        input.start(view, limit, emptyList())
        log.clear()
        draws.removeFirst().run()
        assertThat(log)
            .containsExactly(
                "full",
                "brushRaw:true",
                ballpoint,
                ballpointParams,
                "enabled:true",
                "force:true",
                region(Tool.BALLPOINT, PenColor.BLACK),
            ).inOrder()
    }

    @Test
    fun no_sdk_call_between_pen_down_and_pen_up_with_both_toggles_on() {
        toggles = InkExperiments()
        color = true
        started()
        delayed.removeFirst().first.run()
        input.setTool(Tool.FOUNTAIN, 4f)
        log.clear()
        repeat(STROKES) {
            log += "down"
            cb!!.onBeginRawDrawing(false, point)
            cb!!.onRawDrawingTouchPointMoveReceived(point)
            shadowOf(Looper.getMainLooper()).idle()
            log += "up"
            cb!!.onEndRawDrawing(false, point)
            shadowOf(Looper.getMainLooper()).idle()
            cb!!.onPenUpRefresh(RectF())
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertThat(log).containsExactlyElementsIn(List(STROKES) { listOf("down", "up") }.flatten()).inOrder()
        assertThat(delayed).isEmpty()
    }

    private fun colourStrokeResume(): ArrayDeque<Pair<Runnable, Long>> {
        toggles = InkExperiments(vendorResumeOrder = true, regionPenConfig = false)
        color = true
        started()
        return delayed
    }

    @Test
    fun a_delayed_enable_while_the_pen_is_down_makes_no_sdk_call_until_the_stroke_ends() {
        colourStrokeResume()
        cb!!.onBeginRawDrawing(false, point)
        log.clear()
        delayed.removeFirst().first.run()
        assertThat(log).isEmpty()
        assertThat(delayed.single().second).isEqualTo(OnyxStrokeInput.PEN_DOWN_RECHECK_MS)
        delayed.removeFirst().first.run()
        assertThat(log).isEmpty()
        cb!!.onEndRawDrawing(false, point)
        delayed.removeFirst().first.run()
        assertThat(log).containsExactly("brushRaw:true", ballpoint, ballpointParams, "enabled:true", "force:true").inOrder()
    }

    @Test
    fun a_delayed_enable_after_stop_makes_no_sdk_call() {
        colourStrokeResume()
        input.stop()
        log.clear()
        delayed.removeFirst().first.run()
        assertThat(log).isEmpty()
    }

    @Test
    fun a_ui_refresh_on_a_colour_device_re_enables_at_once_without_the_resume_delay() {
        colourStrokeResume()
        delayed.removeFirst().first.run()
        log.clear()
        input.refresh { log += "redraw" }
        assertThat(log)
            .containsExactly("enabled:false", "redraw", "brushRaw:true", ballpoint, ballpointParams, "enabled:true", "force:true")
            .inOrder()
        assertThat(delayed).isEmpty()
    }

    @Test
    fun turning_the_region_toggle_off_clears_the_config_at_once_and_only_once() {
        toggles = InkExperiments(vendorResumeOrder = false, regionPenConfig = true)
        started()
        log.clear()
        toggles = InkExperiments(vendorResumeOrder = false, regionPenConfig = false)
        input.experimentsChanged()
        input.experimentsChanged()
        assertThat(log).containsExactly("clearRegion")
        input.stop()
        assertThat(log).containsExactly("clearRegion", "close").inOrder()
    }

    @Test
    fun the_default_toggles_are_on() {
        assertThat(InkExperiments()).isEqualTo(InkExperiments(vendorResumeOrder = true, regionPenConfig = true))
        assertThat(InkExperiments.DEFAULT_ON).isTrue()
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

    private companion object {
        const val STROKES = 3
    }
}
