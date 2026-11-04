package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Logs only the enable toggles of a raw drawing session. */
private class TogglingRawDrawing(
    private val log: MutableList<String>,
) : RawDrawing {
    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun open() = Unit

    override fun setEnabled(enabled: Boolean) {
        log += if (enabled) "raw:on" else "raw:off"
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) = Unit

    override fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiRefresherTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val view = View(ctx)
    private val log = mutableListOf<String>()
    private val input =
        OnyxStrokeInput(
            ctx,
            rawDrawing = { _, _ -> TogglingRawDrawing(log) },
            maxPressure = { 4096f },
            afterNextDraw = { _, r -> draws.addLast(r) },
            fullRefresh = { log += "full" },
        )

    private val draws = ArrayDeque<Runnable>()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()
    private var clock = 1_000L

    private fun refresher() =
        UiRefresher(
            input = input,
            afterNextDraw = { draws.addLast(it) },
            postDelayed = { r, d -> delayed += (clock + d) to r },
            cancel = { r -> delayed.removeAll { it.second === r } },
            now = { clock },
            partialRefresh = { log += "partial" },
            fullRefresh = { log += "full" },
        )

    private fun drawFrame() = draws.removeFirst().run()

    private fun advance(ms: Long) {
        clock += ms
        val due = delayed.filter { it.first <= clock }
        delayed.removeAll(due)
        due.forEach { it.second.run() }
    }

    private fun startBoard() = input.start(view, Rect(0, 0, 100, 100), emptyList())

    private fun ready(): UiRefresher {
        val r = refresher()
        startBoard()
        drawFrame()
        log.clear()
        return r
    }

    @Test
    fun raw_drawing_is_enabled_only_after_the_first_frame_then_a_full_refresh_runs_once() {
        refresher()
        startBoard()
        assertThat(log).containsExactly("raw:off")
        drawFrame()
        assertThat(log).containsExactly("raw:off", "full", "raw:on").inOrder()
        assertThat(draws).isEmpty()
    }

    @Test
    fun ui_changes_before_the_first_frame_wait_for_it() {
        val r = refresher()
        startBoard()
        r.uiChanged()
        drawFrame()
        assertThat(draws).isEmpty()
        assertThat(log.count { it == "raw:on" }).isEqualTo(1)
    }

    @Test
    fun tool_change_disables_raw_waits_a_frame_refreshes_then_enables() {
        val r = ready()
        r.uiChanged()
        assertThat(log).containsExactly("raw:off")
        drawFrame()
        assertThat(log).containsExactly("raw:off", "partial", "raw:on").inOrder()
    }

    @Test
    fun chrome_changed_holds_then_full_refreshes_after_next_draw_then_releases() {
        val r = ready()
        r.chromeChanged()
        assertThat(log).containsExactly("raw:off")
        drawFrame()
        assertThat(log).containsExactly("raw:off", "full", "raw:on").inOrder()
    }

    @Test
    fun chrome_changed_during_in_flight_runs_once_after_with_full() {
        val r = ready()
        r.uiChanged()
        r.chromeChanged()
        r.uiChanged()
        drawFrame()
        drawFrame()
        assertThat(draws).isEmpty()
        assertThat(log).containsExactly("raw:off", "partial", "raw:on", "raw:off", "full", "raw:on").inOrder()
    }

    @Test
    fun chrome_changed_before_first_draw_is_ignored() {
        val r = refresher()
        startBoard()
        r.chromeChanged()
        drawFrame()
        assertThat(draws).isEmpty()
        assertThat(log.count { it == "full" }).isEqualTo(1)
    }

    @Test
    fun none_chrome_changed_is_noop() {
        UiRefresh.None.chromeChanged()
    }

    @Test
    fun slider_drag_refreshes_at_most_once_per_interval_and_once_on_release() {
        val r = ready()
        r.uiChanged(settled = false)
        drawFrame()
        repeat(5) {
            advance(20)
            r.uiChanged(settled = false)
        }
        assertThat(draws).isEmpty()
        assertThat(log.count { it == "partial" }).isEqualTo(1)
        advance(UiRefresher.MIN_INTERVAL_MS)
        drawFrame()
        assertThat(log.count { it == "partial" }).isEqualTo(2)
        advance(10)
        r.uiChanged(settled = false)
        r.uiChanged(settled = true)
        drawFrame()
        assertThat(draws).isEmpty()
        assertThat(delayed).isEmpty()
        assertThat(log.count { it == "partial" }).isEqualTo(3)
        assertThat(log.last()).isEqualTo("raw:on")
    }

    @Test
    fun changes_while_a_refresh_is_in_flight_run_once_after_it() {
        val r = ready()
        r.uiChanged()
        r.uiChanged()
        r.uiChanged()
        drawFrame()
        drawFrame()
        assertThat(draws).isEmpty()
        assertThat(log).containsExactly("raw:off", "partial", "raw:on", "raw:off", "partial", "raw:on").inOrder()
    }

    @Test
    fun a_paused_input_stays_disabled_after_a_ui_refresh() {
        val r = ready()
        input.pause()
        log.clear()
        r.uiChanged()
        drawFrame()
        assertThat(log).doesNotContain("raw:on")
    }

    @Test
    fun close_releases_held_raw_drawing_and_ignores_late_frames() {
        val r = ready()
        r.uiChanged()
        r.close()
        assertThat(log.last()).isEqualTo("raw:on")
        log.clear()
        drawFrame()
        r.uiChanged()
        assertThat(log).isEmpty()
        assertThat(draws).isEmpty()
    }

    @Test
    fun a_surface_recreate_with_a_ready_refresher_enables_raw_drawing_only_after_the_next_draw() {
        ready()
        input.stop()
        log.clear()
        startBoard()
        assertThat(log).doesNotContain("raw:on")
        drawFrame()
        assertThat(log.takeLast(2)).containsExactly("full", "raw:on").inOrder()
    }

    @Test
    fun after_next_draw_listener_is_removed_even_when_its_observer_was_merged_into_the_window() {
        val activity =
            org.robolectric.Robolectric
                .buildActivity(android.app.Activity::class.java)
                .setup()
                .get()
        val v = View(activity)
        var runs = 0
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        v.afterNextDraw(main) { runs++ }
        activity.setContentView(v)
        val observer = v.viewTreeObserver
        observer.dispatchOnPreDraw()
        observer.dispatchOnPreDraw()
        observer.dispatchOnPreDraw()
        org.robolectric.shadows.ShadowLooper
            .idleMainLooper()
        assertThat(runs).isEqualTo(1)
    }
}
