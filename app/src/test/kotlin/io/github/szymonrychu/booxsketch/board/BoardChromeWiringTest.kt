package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import io.github.szymonrychu.booxsketch.ui.AppShell
import io.github.szymonrychu.booxsketch.ui.BOARD_TAB_ID
import io.github.szymonrychu.booxsketch.ui.ShellState
import io.github.szymonrychu.booxsketch.ui.TAG_TAB_BAR
import io.github.szymonrychu.booxsketch.ui.TabRegistry
import io.github.szymonrychu.booxsketch.ui.TabSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardChromeWiringTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val strokes = Channel<StrokeEvent>(Channel.UNLIMITED)
    private val input = FreezingInput(strokes.receiveAsFlow())
    private val state = ShellState()
    private var host: View? = null

    private fun vm() =
        BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun show(
        withChrome: Boolean = true,
        insets: WindowInsets = WindowInsets(0, 0, 0, 0),
        dock: DockContent? = null,
        panel: (@Composable () -> Unit)? = null,
        size: androidx.compose.runtime.State<DpSize>? = null,
    ) {
        val registry = TabRegistry()
        registry.register(TabSpec(BOARD_TAB_ID, "Drawings", 10) { })
        registry.register(TabSpec("spec", "Spec", 20) { })
        val vm = vm()
        compose.setContent {
            host = LocalView.current
            val shellModifier = size?.let { Modifier.size(it.value) } ?: Modifier
            AppShell(
                modifier = shellModifier,
                tabs = registry,
                board = { chrome ->
                    val c = if (withChrome) chrome else null
                    if (dock == null) {
                        BoardScreen(vm, input, chrome = c, panel = panel)
                    } else {
                        BoardScreenWith(vm, input, chrome = c, dock = dock, panel = panel)
                    }
                },
                state = state,
                insets = insets,
            )
        }
    }

    /** A dock whose hidden-state indicator rect appears only once [measured] is true; the tap hides or shows. */
    private fun lateDock(measured: androidx.compose.runtime.State<Boolean>): DockContent =
        { p ->
            val rects =
                if (p.visible) {
                    listOf(Rect(0, 500, 400, 600))
                } else if (measured.value) {
                    listOf(Rect(0, 0, 48, 48))
                } else {
                    emptyList()
                }
            var reported by remember { mutableStateOf<List<Rect>?>(null) }
            SideEffect {
                if (rects != reported) {
                    reported = rects
                    p.onExcludeChange(rects)
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .testTag(if (p.visible) "fake_hide" else "fake_show")
                    .size(48.dp)
                    .clickable { if (p.visible) p.onHide() else p.onShow() },
            )
        }

    private fun lifecycle(event: Lifecycle.Event) {
        compose.runOnUiThread { ((host!!.context as LifecycleOwner).lifecycle as LifecycleRegistry).handleLifecycleEvent(event) }
        compose.waitForIdle()
    }

    private fun holds() = input.log.count { it == "hold" }

    private fun releases() = input.log.count { it == "release" }

    private fun surface(): BoardSurfaceView? {
        fun find(v: View): BoardSurfaceView? =
            if (v is BoardSurfaceView) {
                v
            } else {
                (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            }
        return find(host!!.rootView)
    }

    /** Robolectric creates no real surface: report it so the board starts its input. */
    private fun createSurface() {
        compose.runOnUiThread { surface()!!.let { it.surfaceCreated(it.holder) } }
        compose.waitForIdle()
    }

    private fun rectOf(tag: String): Rect {
        val d = compose.density.density
        val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return Rect((b.left.value * d).toInt(), (b.top.value * d).toInt(), (b.right.value * d).toInt(), (b.bottom.value * d).toInt())
    }

    /** Log index at which the last outstanding hold is released (raw drawing back on); -1 if never. */
    private fun enabledAt(): Int {
        var held = 0
        var at = -1
        input.log.forEachIndexed { i, e ->
            if (e == "hold") held++
            if (e == "release") {
                held--
                if (held == 0) at = i
            }
        }
        return at
    }

    /** Indices of `start:[]` calls made while no hold was outstanding, i.e. raw drawing on with no exclude. */
    private fun emptyStartsWhileEnabled(): List<Int> {
        var held = 0
        return input.log
            .withIndex()
            .filter { (_, e) ->
                if (e == "hold") held++
                if (e == "release") held--
                e == "start:[]" && held <= 0
            }.map { it.index }
    }

    private fun lastStart(): String = input.log.last { it.startsWith("start:") }

    @Test
    fun board_view_fills_screen_with_dock_overlaid() {
        show()
        compose.waitForIdle()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val d = compose.density.density
        val s = surface()!!
        assertThat(s.width).isEqualTo(((root.right - root.left).value * d).toInt())
        assertThat(s.height).isEqualTo(((root.bottom - root.top).value * d).toInt())
        compose.onNodeWithTag(TAG_DOCK).assertIsDisplayed()
    }

    @Test
    fun board_ignores_system_bar_insets() {
        show(insets = WindowInsets(0, 40, 0, 40))
        compose.waitForIdle()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val d = compose.density.density
        val s = surface()!!
        assertThat(s.width).isEqualTo(((root.right - root.left).value * d).toInt())
        assertThat(s.height).isEqualTo(((root.bottom - root.top).value * d).toInt())
        // The chrome over the board keeps clear of the bars.
        assertThat(rectOf(TAG_TAB_BAR).top).isAtLeast(40)
        assertThat(rectOf(TAG_DOCK).bottom).isAtMost((root.bottom.value * d).toInt() - 40)
    }

    @Test
    fun hold_covers_the_gap_until_the_hidden_indicator_rect_is_measured() {
        val indicatorMeasured = mutableStateOf(false)
        val dockRect = Rect(0, 500, 400, 600)
        val indicatorRect = Rect(0, 0, 48, 48)
        show(
            dock = { p ->
                // A dock whose indicator is measured late: the hidden state first reports no rect.
                val rects =
                    if (p.visible) {
                        listOf(dockRect)
                    } else if (indicatorMeasured.value) {
                        listOf(indicatorRect)
                    } else {
                        emptyList()
                    }
                var reported by remember { mutableStateOf<List<Rect>?>(null) }
                SideEffect {
                    if (rects != reported) {
                        reported = rects
                        p.onExcludeChange(rects)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .testTag(if (p.visible) "fake_hide" else "fake_show")
                        .size(48.dp)
                        .clickable {
                            p.ui.chromeChanged()
                            if (p.visible) p.onHide() else p.onShow()
                        },
                )
            },
        )
        createSurface()
        input.log.clear()

        compose.onNodeWithTag("fake_hide").performClick()
        compose.waitForIdle()

        // Hidden state reported, but no rect yet: the hold is still outstanding and no empty-exclude start ran with it released.
        assertThat(input.log.first()).isEqualTo("hold")
        assertThat(enabledAt()).isEqualTo(-1)
        assertThat(emptyStartsWhileEnabled()).isEmpty()

        compose.runOnUiThread { indicatorMeasured.value = true }
        compose.waitForIdle()

        assertThat(input.log).contains("start:${listOf(indicatorRect)}")
        assertThat(enabledAt()).isGreaterThan(input.log.lastIndexOf("start:${listOf(indicatorRect)}"))
    }

    @Test
    fun auto_hide_takes_the_hold_until_the_indicator_rect_is_applied() {
        show()
        createSurface()
        input.log.clear()
        compose.mainClock.autoAdvance = false
        strokes.trySend(StrokeEvent.Committed(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))))
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        val want = "start:${listOf(rectOf(TAG_INDICATOR))}"
        assertThat(input.log).contains("hold")
        assertThat(input.log.indexOf("hold")).isLessThan(input.log.indexOf(want))
        assertThat(emptyStartsWhileEnabled()).isEmpty()
        assertThat(enabledAt()).isGreaterThan(input.log.lastIndexOf(want))
    }

    @Test
    fun auto_hide_waits_while_a_stroke_is_in_progress_and_drops_nothing() {
        show()
        createSurface()
        input.log.clear()
        compose.mainClock.autoAdvance = false
        strokes.trySend(StrokeEvent.Committed(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))))
        compose.mainClock.advanceTimeBy(100)
        input.strokeInProgress = true
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS * 3)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(state.chromeVisible).isTrue()
        assertThat(input.log).isEmpty()

        input.strokeInProgress = false
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(state.chromeVisible).isFalse()
        assertThat(input.log.first()).isEqualTo("hold")
    }

    @Test
    fun hide_updates_exclude_while_held() {
        show()
        createSurface()
        val dock = rectOf(TAG_DOCK)
        val bar = rectOf(TAG_TAB_BAR)
        assertThat(lastStart()).isEqualTo("start:${listOf(dock, bar)}")
        input.log.clear()

        compose.onNodeWithTag(TAG_HIDE).performClick()
        compose.waitForIdle()
        val indicator = rectOf(TAG_INDICATOR)

        assertThat(input.log.first()).isEqualTo("hold")
        assertThat(input.log).doesNotContain("start:${listOf(dock, bar)}")
        assertThat(input.log).contains("start:${listOf(indicator)}")
        assertThat(enabledAt()).isGreaterThan(input.log.lastIndexOf("start:${listOf(indicator)}"))
    }

    @Test
    fun hidden_toolbar_never_leaves_a_gap_with_raw_drawing_enabled_and_no_excludes() {
        show()
        createSurface()
        val dock = rectOf(TAG_DOCK)
        input.log.clear()

        compose.onNodeWithTag(TAG_HIDE).performClick()
        compose.waitForIdle()
        val indicator = rectOf(TAG_INDICATOR)

        // Replay the log: whenever no hold is outstanding (raw drawing is on), the board must be
        // started with the indicator excluded; an empty exclude list there is a gap.
        var held = 0
        var started = "start:${listOf(dock)}"
        var gapFree = true
        for (entry in input.log) {
            when {
                entry == "hold" -> {
                    held++
                }

                entry == "release" -> {
                    held--
                    if (held == 0 && started != "start:${listOf(indicator)}") gapFree = false
                }

                entry.startsWith("start:") -> {
                    started = entry
                    if (held == 0 && entry == "start:[]") gapFree = false
                }
            }
        }
        assertThat(held).isEqualTo(0)
        assertThat(started).isEqualTo("start:${listOf(indicator)}")
        assertThat(gapFree).isTrue()
    }

    @Test
    fun show_applies_toolbar_rect_before_release() {
        show()
        createSurface()
        val dock = rectOf(TAG_DOCK)
        val bar = rectOf(TAG_TAB_BAR)
        compose.onNodeWithTag(TAG_HIDE).performClick()
        compose.waitForIdle()
        input.log.clear()

        compose.onNodeWithTag(TAG_INDICATOR).performClick()
        compose.waitForIdle()

        val want = "start:${listOf(dock, bar)}"
        assertThat(input.log.first()).isEqualTo("hold")
        assertThat(input.log).contains(want)
        assertThat(enabledAt()).isGreaterThan(input.log.indexOf(want))
    }

    @Test
    fun committed_stroke_arms_auto_hide_and_hides_after_gap() {
        show()
        createSurface()
        compose.mainClock.autoAdvance = false
        strokes.trySend(StrokeEvent.Committed(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))))
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExist()
        compose.onNodeWithTag(TAG_INDICATOR).assertIsDisplayed()
        assertThat(lastStart()).isEqualTo("start:${listOf(rectOf(TAG_INDICATOR))}")
    }

    @Test
    fun leaving_drawings_stops_input_and_returning_starts_held_then_releases_after_first_draw() {
        show()
        createSurface()
        input.log.clear()

        compose.runOnUiThread { Snapshot.withMutableSnapshot { state.select("spec") } }
        compose.waitForIdle()
        assertThat(input.log).contains("stop")
        input.log.clear()

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { Snapshot.withMutableSnapshot { state.select(BOARD_TAB_ID) } }
        compose.mainClock.advanceTimeByFrame()
        assertThat(input.log.first()).isEqualTo("hold")
        assertThat(input.log).doesNotContain("release")
        createSurface()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(input.log.indexOf("hold")).isLessThan(input.log.indexOfFirst { it.startsWith("start:") })
        assertThat(input.log.indexOf("release")).isGreaterThan(input.log.indexOfFirst { it.startsWith("start:") })
    }

    @Test
    fun hide_without_chrome_takes_no_hold_that_never_settles() {
        show(withChrome = false, dock = lateDock(mutableStateOf(false)))
        createSurface()
        input.log.clear()

        compose.onNodeWithTag("fake_hide").performClick()
        compose.waitForIdle()

        assertThat(holds()).isEqualTo(releases())
    }

    @Test
    fun a_hold_pending_at_dispose_is_released() {
        show(dock = lateDock(mutableStateOf(false)))
        createSurface()
        input.log.clear()
        compose.onNodeWithTag("fake_hide").performClick()
        compose.waitForIdle()
        assertThat(holds()).isEqualTo(1)
        assertThat(releases()).isEqualTo(0)

        compose.runOnUiThread { Snapshot.withMutableSnapshot { state.select("spec") } }
        compose.waitForIdle()

        assertThat(input.log).contains("stop")
        assertThat(releases()).isEqualTo(holds())
    }

    @Test
    fun a_hold_pending_across_pause_is_released_once_settled_and_never_leaks() {
        val measured = mutableStateOf(false)
        show(dock = lateDock(measured))
        createSurface()
        input.log.clear()
        compose.onNodeWithTag("fake_hide").performClick()
        compose.waitForIdle()
        assertThat(holds()).isEqualTo(1)
        assertThat(releases()).isEqualTo(0)

        lifecycle(Lifecycle.Event.ON_PAUSE)
        compose.runOnUiThread { measured.value = true }
        compose.waitForIdle()
        lifecycle(Lifecycle.Event.ON_RESUME)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        // The compose frame clock keeps running while paused, so the release may land before the resume;
        // the input's own pause flag keeps raw drawing off until then (OnyxLiveInkTest).
        assertThat(releases()).isEqualTo(holds())
        assertThat(input.log).contains("start:${listOf(Rect(0, 0, 48, 48))}")
    }

    @Test
    fun null_chrome_keeps_dock_visible() {
        show(withChrome = false)
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_DOCK).assertIsDisplayed()
        compose.onNodeWithTag(TAG_INDICATOR).assertDoesNotExist()
    }

    private val testPanel: @Composable () -> Unit = { Text("panel") }

    private fun commitStroke() {
        strokes.trySend(StrokeEvent.Committed(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))))
    }

    private fun lastStartWith(rect: Rect): Int = input.log.indexOfLast { it.startsWith("start:") && it.contains(rect.toString()) }

    @Test
    fun panel_show_holds_raw_drawing_until_panel_rect_is_in_excludes() {
        show(panel = testPanel)
        createSurface()
        input.log.clear()

        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
        val panel = rectOf(TAG_PANEL_HOST)

        assertThat(state.panelVisible).isTrue()
        assertThat(input.log.first()).isEqualTo("hold")
        val started = lastStartWith(panel)
        assertThat(started).isAtLeast(0)
        assertThat(enabledAt()).isGreaterThan(started)
        assertThat(emptyStartsWhileEnabled()).isEmpty()
        assertThat(releases()).isEqualTo(holds())
    }

    @Test
    fun panel_hide_via_auto_hide_releases_hold_once() {
        show(panel = testPanel)
        createSurface()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
        input.log.clear()

        compose.mainClock.autoAdvance = false
        commitStroke()
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_PANEL_HOST).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExist()
        val indicator = rectOf(TAG_PANEL_INDICATOR)
        assertThat(state.panelVisible).isFalse()
        assertThat(input.log.first()).isEqualTo("hold")
        // Dock and panel hide in one burst: the start that carries both indicators comes before raw drawing is back on.
        val dockIndicator = rectOf(TAG_INDICATOR).toString()
        val started = input.log.indexOfLast { it.startsWith("start:") && it.contains(indicator.toString()) && it.contains(dockIndicator) }
        assertThat(started).isAtLeast(0)
        assertThat(enabledAt()).isGreaterThan(started)
        assertThat(emptyStartsWhileEnabled()).isEmpty()
        assertThat(releases()).isEqualTo(holds())
    }

    @Test
    fun auto_hide_refused_during_stroke_keeps_panel_until_quiet() {
        show(panel = testPanel)
        createSurface()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
        input.log.clear()

        compose.mainClock.autoAdvance = false
        commitStroke()
        compose.mainClock.advanceTimeBy(100)
        input.strokeInProgress = true
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS * 3)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(state.panelVisible).isTrue()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertIsDisplayed()
        assertThat(input.log).isEmpty()

        input.strokeInProgress = false
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(state.panelVisible).isFalse()
        assertThat(input.log.first()).isEqualTo("hold")
        assertThat(releases()).isEqualTo(holds())
    }

    private fun fingerTap() {
        fun ev(
            action: Int,
            time: Long,
        ): MotionEvent {
            val props =
                MotionEvent.PointerProperties().also {
                    it.id = 0
                    it.toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            val coords =
                MotionEvent.PointerCoords().also {
                    it.x = 200f
                    it.y = 200f
                }
            return MotionEvent.obtain(1_000L, time, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        val v = surface()!!
        compose.runOnUiThread {
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 1_000L))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 1_050L))
        }
        compose.waitForIdle()
    }

    @Test
    fun tap_on_board_hides_dock_and_panel_with_one_hold_release_pair() {
        show(panel = testPanel)
        createSurface()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
        input.log.clear()

        fingerTap()

        compose.onNodeWithTag(TAG_PANEL_HOST).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExist()
        assertThat(state.chromeVisible).isFalse()
        assertThat(state.panelVisible).isFalse()
        val panelIndicator = rectOf(TAG_PANEL_INDICATOR).toString()
        val dockIndicator = rectOf(TAG_INDICATOR).toString()
        val started = input.log.indexOfLast { it.startsWith("start:") && it.contains(panelIndicator) && it.contains(dockIndicator) }
        assertThat(input.log.first()).isEqualTo("hold")
        assertThat(started).isAtLeast(0)
        assertThat(enabledAt()).isGreaterThan(started)
        assertThat(emptyStartsWhileEnabled()).isEmpty()
        // One board hold for both overlays, plus at most the refresher's own hold for the full refresh.
        assertThat(holds()).isAtMost(2)
        assertThat(releases()).isEqualTo(holds())
    }

    @Test
    fun tap_with_everything_hidden_changes_nothing() {
        show(panel = testPanel)
        createSurface()
        compose.onNodeWithTag(TAG_HIDE).performClick()
        compose.waitForIdle()
        assertThat(state.chromeVisible).isFalse()
        assertThat(state.panelVisible).isFalse()
        input.log.clear()

        fingerTap()

        assertThat(input.log).isEmpty()
        compose.onNodeWithTag(TAG_INDICATOR).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).assertIsDisplayed()
    }

    @Test
    fun size_change_with_dock_visible_restarts_with_new_dock_rect() {
        val size = mutableStateOf(DpSize(400.dp, 300.dp))
        show(size = size)
        createSurface()
        val before = rectOf(TAG_DOCK)
        input.log.clear()

        compose.runOnUiThread { size.value = DpSize(300.dp, 400.dp) }
        compose.waitForIdle()
        val after = rectOf(TAG_DOCK)

        assertThat(after).isNotEqualTo(before)
        assertThat(lastStart()).contains(after.toString())
        assertThat(emptyStartsWhileEnabled()).isEmpty()
        assertThat(releases()).isEqualTo(holds())
    }

    @Test
    fun relayout_hold_waits_for_the_dock_to_report_non_empty_rects() {
        val measured = mutableStateOf(true)
        val dock: DockContent =
            { p ->
                val rects = if (measured.value) listOf(Rect(0, 500, 400, 600)) else emptyList()
                var reported by remember { mutableStateOf<List<Rect>?>(null) }
                SideEffect {
                    if (rects != reported) {
                        reported = rects
                        p.onExcludeChange(rects)
                    }
                }
            }
        show(dock = dock)
        createSurface()
        compose.waitForIdle()
        // The new layout has not measured the dock yet: it reports no rect.
        compose.runOnUiThread { measured.value = false }
        compose.waitForIdle()
        input.log.clear()

        compose.runOnUiThread {
            val activity = host!!.context as android.app.Activity
            val config = android.content.res.Configuration(activity.resources.configuration)
            config.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
            config.screenWidthDp += 10
            (activity as androidx.activity.ComponentActivity).onConfigurationChanged(config)
        }
        compose.waitForIdle()
        // Raw drawing stays held while the dock has no rect in the new layout (the layout hold and the
        // refresher's hold for the full refresh, which only a real draw releases).
        assertThat(holds()).isEqualTo(2)
        assertThat(releases()).isEqualTo(0)

        compose.runOnUiThread { measured.value = true }
        compose.waitForIdle()
        // The layout hold is released once, after the input restarted with the new rects.
        assertThat(releases()).isEqualTo(1)
        assertThat(input.log.indexOf("release")).isGreaterThan(input.log.indexOfLast { it.startsWith("start:") } - 1)
    }

    @Test
    fun relayout_hold_ignores_the_stale_rect_reported_before_the_rotation() {
        val oldRect = Rect(0, 500, 400, 600)
        val newRect = Rect(0, 300, 600, 400)
        val current = mutableStateOf(oldRect)
        val dock: DockContent =
            { p ->
                val rects = listOf(current.value)
                var reported by remember { mutableStateOf<List<Rect>?>(null) }
                SideEffect {
                    if (rects != reported) {
                        reported = rects
                        p.onExcludeChange(rects)
                    }
                }
            }
        show(dock = dock)
        createSurface()
        compose.waitForIdle()
        input.log.clear()

        compose.runOnUiThread {
            val activity = host!!.context as android.app.Activity
            val config = android.content.res.Configuration(activity.resources.configuration)
            config.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
            config.screenWidthDp += 10
            (activity as androidx.activity.ComponentActivity).onConfigurationChanged(config)
        }
        compose.waitForIdle()
        // The old rect is still stored but no report came from the new layout: raw drawing stays held
        // (the layout hold and the refresher's hold for the full refresh).
        assertThat(holds()).isEqualTo(2)
        assertThat(releases()).isEqualTo(0)

        compose.runOnUiThread { current.value = newRect }
        compose.waitForIdle()
        assertThat(releases()).isEqualTo(1)
        // The restarted input carries the new rect (clipped to the window), not the stale one.
        assertThat(lastStart()).contains("Rect(0, 300 -")
        assertThat(lastStart()).doesNotContain("Rect(0, 500 -")
        assertThat(input.log.indexOf("release")).isGreaterThan(input.log.indexOfLast { it.startsWith("start:") } - 1)
    }
}
