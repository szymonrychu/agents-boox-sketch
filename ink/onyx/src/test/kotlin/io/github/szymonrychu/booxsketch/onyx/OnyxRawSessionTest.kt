package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records every call a raw drawing session makes, in order, across all created helpers. */
private class FakeRawDrawing(
    private val log: MutableList<String>,
    private val n: Int,
) : RawDrawing {
    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) {
        log += "limit$n:${exclude.size}"
    }

    override fun open() {
        log += "open$n"
    }

    override fun setEnabled(enabled: Boolean) {
        log += "enabled$n:$enabled"
    }

    override fun setBrush(
        style: Int,
        width: Float,
    ) {
        log += "brush$n"
    }

    override fun close() {
        log += "close$n"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxRawSessionTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val view = View(ctx)
    private val log = mutableListOf<String>()
    private var created = 0
    private val input =
        OnyxStrokeInput(ctx, rawDrawing = { _, _ -> FakeRawDrawing(log, ++created) }, maxPressure = { 4096f })

    private fun limit() = Rect(0, 0, 100, 100)

    @Test
    fun repeated_layout_on_the_same_view_creates_one_helper_and_only_updates_rects() {
        input.start(view, limit(), emptyList())
        log.clear()
        input.start(view, limit(), listOf(Rect(0, 90, 100, 100)))
        input.start(view, limit(), listOf(Rect(0, 90, 100, 100), Rect(0, 0, 10, 10)))
        assertThat(created).isEqualTo(1)
        assertThat(log).containsExactly("limit1:1", "limit1:2").inOrder()
    }

    @Test
    fun first_start_sets_rects_brush_opens_and_enables() {
        input.start(view, limit(), emptyList())
        assertThat(log).containsExactly("limit1:0", "brush1", "open1", "enabled1:true").inOrder()
    }

    @Test
    fun a_new_view_closes_the_previous_helper_before_creating_another() {
        input.start(view, limit(), emptyList())
        input.start(View(ctx), limit(), emptyList())
        assertThat(created).isEqualTo(2)
        assertThat(log.indexOf("close1")).isLessThan(log.indexOf("limit2:0"))
    }

    @Test
    fun stop_closes_raw_drawing_once_and_start_after_stop_recreates() {
        input.start(view, limit(), emptyList())
        input.stop()
        input.stop()
        assertThat(log.count { it == "close1" }).isEqualTo(1)
        input.start(view, limit(), emptyList())
        assertThat(created).isEqualTo(2)
    }

    @Test
    fun pause_disables_and_resume_enables_and_reapplies_brush() {
        input.start(view, limit(), emptyList())
        log.clear()
        input.pause()
        input.resume()
        assertThat(log).containsExactly("enabled1:false", "enabled1:true", "brush1").inOrder()
    }

    @Test
    fun start_while_paused_keeps_raw_drawing_disabled() {
        input.pause()
        input.start(view, limit(), emptyList())
        assertThat(log.last()).isEqualTo("enabled1:false")
    }

    @Test
    fun refresh_toggles_raw_drawing_off_around_the_redraw() {
        input.start(view, limit(), emptyList())
        log.clear()
        input.refresh { log += "redraw" }
        assertThat(log).containsExactly("enabled1:false", "redraw", "enabled1:true", "brush1").inOrder()
    }

    @Test
    fun refresh_re_enables_even_when_the_redraw_throws() {
        input.start(view, limit(), emptyList())
        log.clear()
        runCatching { input.refresh { error("boom") } }
        assertThat(log).contains("enabled1:true")
    }

    @Test
    fun refresh_while_paused_or_stopped_only_redraws() {
        input.refresh { log += "redraw-stopped" }
        input.start(view, limit(), emptyList())
        input.pause()
        log.clear()
        input.refresh { log += "redraw-paused" }
        assertThat(log).containsExactly("redraw-paused")
    }

    @Test
    fun raw_pressure_is_normalised_by_the_device_maximum() {
        val pts = listOf(TouchPoint(1f, 1f, 2048f, 1f, 0, 0, 0L), TouchPoint(2f, 2f, 5000f, 1f, 0, 0, 1L))
        val stroke = OnyxStrokeInput.strokeFrom(pts, Tool.BALLPOINT, 4f, "s", maxPressure = 4096f)!!
        assertThat(stroke.points.map { it.pressure }).containsExactly(0.5f, 1f).inOrder()
    }

    @Test
    fun unknown_maximum_falls_back_to_the_safe_constant() {
        assertThat(OnyxStrokeInput.normalisePressure(2048f, 0f)).isEqualTo(2048f / OnyxStrokeInput.FALLBACK_MAX_PRESSURE)
        assertThat(OnyxStrokeInput.normalisePressure(2048f, Float.NaN)).isEqualTo(2048f / OnyxStrokeInput.FALLBACK_MAX_PRESSURE)
        assertThat(OnyxStrokeInput.normalisePressure(-1f, 4096f)).isEqualTo(0f)
    }
}
