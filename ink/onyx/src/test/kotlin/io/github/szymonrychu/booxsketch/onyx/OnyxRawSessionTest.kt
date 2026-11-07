package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.data.TouchPointList
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        color: Int,
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
    private val draws = ArrayDeque<Runnable>()
    private val input =
        OnyxStrokeInput(
            ctx,
            rawDrawing = { _, _ -> FakeRawDrawing(log, ++created) },
            maxPressure = { 4096f },
            afterNextDraw = { _, r -> draws.addLast(r) },
            fullRefresh = { log += "full" },
        )

    private fun drawFrame() = draws.removeFirst().run()

    private fun limit() = Rect(0, 0, 100, 100)

    @Test
    fun repeated_layout_on_the_same_view_creates_one_helper_and_unchanged_rects_do_nothing() {
        input.start(view, limit(), listOf(Rect(0, 90, 100, 100)))
        log.clear()
        input.start(view, limit(), listOf(Rect(0, 90, 100, 100)))
        assertThat(created).isEqualTo(1)
        assertThat(log).isEmpty()
    }

    @Test
    fun changed_rects_are_applied_and_raw_drawing_is_reopened_as_in_the_vendor_demo() {
        input.start(view, limit(), emptyList())
        drawFrame()
        log.clear()
        input.start(view, limit(), listOf(Rect(0, 90, 100, 100)))
        input.start(view, limit(), listOf(Rect(0, 90, 100, 100), Rect(0, 0, 10, 10)))
        assertThat(created).isEqualTo(1)
        assertThat(log)
            .containsExactly(
                "limit1:1",
                "open1",
                "brush1",
                "enabled1:true",
                "brush1",
                "limit1:2",
                "open1",
                "brush1",
                "enabled1:true",
                "brush1",
            ).inOrder()
    }

    @Test
    fun changed_rects_while_paused_keep_raw_drawing_disabled() {
        input.start(view, limit(), emptyList())
        drawFrame()
        input.pause()
        log.clear()
        input.start(view, limit(), listOf(Rect(0, 0, 5, 5)))
        assertThat(log.last()).isEqualTo("enabled1:false")
    }

    @Test
    fun stroke_in_progress_between_raw_begin_and_end() {
        var cb: RawInputCallback? = null
        val i =
            OnyxStrokeInput(ctx, rawDrawing = { _, c ->
                cb = c
                FakeRawDrawing(log, ++created)
            }, maxPressure = { 4096f })
        i.start(view, limit(), emptyList())
        val p = TouchPoint(1f, 1f, 2048f, 1f, 0, 0, 0L)
        assertThat(i.strokeInProgress).isFalse()
        cb!!.onBeginRawDrawing(false, p)
        assertThat(i.strokeInProgress).isTrue()
        cb!!.onEndRawDrawing(false, p)
        assertThat(i.strokeInProgress).isFalse()
        cb!!.onBeginRawErasing(false, p)
        assertThat(i.strokeInProgress).isTrue()
        i.stop()
        assertThat(i.strokeInProgress).isFalse()
    }

    @Test
    fun stop_then_start_then_stroke_delivers_the_stroke() =
        runBlocking {
            var cb: RawInputCallback? = null
            val restartable =
                OnyxStrokeInput(ctx, newId = { "s1" }, rawDrawing = { _, c ->
                    cb = c
                    FakeRawDrawing(log, ++created)
                }, maxPressure = { 4096f })
            restartable.start(view, limit(), emptyList())
            restartable.stop()
            restartable.start(view, limit(), emptyList())
            assertThat(created).isEqualTo(2)
            val list = TouchPointList().apply { add(TouchPoint(1f, 1f, 2048f, 1f, 0, 0, 0L)) }
            cb!!.onRawDrawingTouchPointListReceived(list)
            val ev = withTimeout(1_000) { restartable.events.first() }
            assertThat((ev as StrokeEvent.Committed).stroke.id).isEqualTo("s1")
        }

    @Test
    fun first_start_sets_rects_opens_brushes_and_enables_only_after_the_next_draw_with_a_full_refresh() {
        input.start(view, limit(), emptyList())
        // The open resets the firmware style and starts the pen (the disable after it is a no-op in the SDK): brush right after it.
        assertThat(log).containsExactly("limit1:1", "open1", "brush1", "enabled1:false").inOrder()
        log.clear()
        drawFrame()
        assertThat(log).containsExactly("full", "enabled1:true", "brush1").inOrder()
    }

    @Test
    fun start_after_the_surface_is_recreated_does_not_enable_raw_drawing_before_the_next_draw() {
        input.start(view, limit(), emptyList())
        drawFrame()
        input.stop()
        log.clear()
        input.start(view, limit(), emptyList())
        assertThat(log).doesNotContain("enabled2:true")
        assertThat(log.last()).isEqualTo("enabled2:false")
        drawFrame()
        assertThat(log.takeLast(3)).containsExactly("full", "enabled2:true", "brush2").inOrder()
    }

    @Test
    fun a_session_replaced_before_the_draw_still_releases_its_hold_without_a_stale_refresh() {
        input.start(view, limit(), emptyList())
        input.stop()
        input.start(view, limit(), emptyList())
        log.clear()
        drawFrame()
        assertThat(log).isEmpty()
        drawFrame()
        assertThat(log).containsExactly("full", "enabled2:true", "brush2").inOrder()
    }

    @Test
    fun a_session_stopped_before_the_draw_never_enables() {
        input.start(view, limit(), emptyList())
        input.stop()
        log.clear()
        drawFrame()
        assertThat(log).isEmpty()
    }

    @Test
    fun a_new_view_closes_the_previous_helper_before_creating_another() {
        input.start(view, limit(), emptyList())
        input.start(View(ctx), limit(), emptyList())
        assertThat(created).isEqualTo(2)
        assertThat(log.indexOf("close1")).isLessThan(log.indexOf("limit2:1"))
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
        drawFrame()
        log.clear()
        input.pause()
        input.resume()
        assertThat(log).containsExactly("enabled1:false", "enabled1:true", "brush1").inOrder()
    }

    @Test
    fun eraser_keeps_raw_drawing_off_across_pause_hold_and_refresh_until_it_is_left() {
        input.start(view, limit(), emptyList())
        drawFrame()
        log.clear()
        input.setEraser(true)
        assertThat(log).containsExactly("enabled1:false")
        log.clear()
        input.pause()
        input.resume()
        input.holdUi()
        input.releaseUi()
        input.refresh { log += "redraw" }
        assertThat(log).doesNotContain("enabled1:true")
        log.clear()
        input.setEraser(false)
        assertThat(log).containsExactly("enabled1:true", "brush1").inOrder()
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
        drawFrame()
        log.clear()
        input.refresh { log += "redraw" }
        assertThat(log).containsExactly("enabled1:false", "redraw", "enabled1:true", "brush1").inOrder()
    }

    @Test
    fun refresh_re_enables_even_when_the_redraw_throws() {
        input.start(view, limit(), emptyList())
        drawFrame()
        log.clear()
        runCatching { input.refresh { error("boom") } }
        assertThat(log).contains("enabled1:true")
    }

    @Test
    fun refresh_while_paused_or_stopped_only_redraws() {
        input.refresh { log += "redraw-stopped" }
        input.start(view, limit(), emptyList())
        drawFrame()
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
