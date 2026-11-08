package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.pen.RawInputCallback
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class LoggingRawDrawing(
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

/** A firmware region pen config left by an older build is cleared once per session, before the first open (T-78). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxStaleRegionConfigTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val view = View(ctx)
    private val log = mutableListOf<String>()
    private val input =
        OnyxStrokeInput(
            ctx,
            rawDrawing = { _: View, _: RawInputCallback -> LoggingRawDrawing(log) },
            afterNextDraw = { _, _ -> },
            fullRefresh = { },
        )

    @Test
    fun the_region_config_is_cleared_once_before_the_first_open_and_not_on_later_opens() {
        val limit = Rect(0, 0, 100, 100)
        input.start(view, limit, emptyList())
        assertThat(log.take(3)).containsExactly("clearRegion", "limit", "open").inOrder()
        log.clear()
        input.start(view, limit, listOf(Rect(0, 0, 10, 10)))
        input.start(view, limit, listOf(Rect(0, 0, 20, 20)))
        assertThat(log).doesNotContain("clearRegion")
        assertThat(log.count { it == "open" }).isEqualTo(2)
        log.clear()
        input.stop()
        assertThat(log).containsExactly("close")
    }

    /** The v0.19.2 call sequence of an open: forced pause, idle timeout, brush, with no region config set. */
    @Test
    fun an_open_forces_the_pause_then_sets_the_idle_timeout_and_the_brush() {
        input.start(view, Rect(0, 0, 100, 100), emptyList())
        val style = BrushMapping.style(Tool.BALLPOINT)
        val w = BrushMapping.width(Tool.BALLPOINT, 4f)
        assertThat(log)
            .containsExactly(
                "clearRegion",
                "limit",
                "open",
                "force:false",
                "idle:${OnyxStrokeInput.PEN_IDLE_MS}",
                "brush:$style:$w:${Integer.toHexString(PenColor.BLACK.argb)}",
                "enabled:false",
            ).inOrder()
    }
}
