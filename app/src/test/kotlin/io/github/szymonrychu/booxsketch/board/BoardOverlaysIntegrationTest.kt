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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/** Replays of [FreezingInput.log] for combined overlay changes: panel, strip, popup, rotation and auto-hide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class BoardOverlaysIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val strokes = Channel<StrokeEvent>(Channel.UNLIMITED)
    private val input = FreezingInput(strokes.receiveAsFlow())
    private val state = ShellState()
    private var host: View? = null
    private val panel: @Composable () -> Unit = { Text("panel") }

    private val size = mutableStateOf(DpSize(600.dp, 900.dp))

    private fun show() {
        val registry = TabRegistry()
        registry.register(TabSpec(BOARD_TAB_ID, "Drawings", 10) { })
        registry.register(TabSpec("spec", "Spec", 20) { })
        val vm = BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        compose.setContent {
            host = LocalView.current
            AppShell(
                modifier = Modifier.size(size.value),
                tabs = registry,
                board = { chrome -> BoardScreen(vm, input, chrome = chrome, panel = panel) },
                state = state,
                insets = WindowInsets(0, 0, 0, 0),
            )
        }
        // Robolectric creates no real surface: report it so the board starts its input.
        compose.waitForIdle()
        compose.runOnUiThread { surface()!!.let { it.surfaceCreated(it.holder) } }
        compose.waitForIdle()
    }

    private fun surface(): BoardSurfaceView? {
        fun find(v: View): BoardSurfaceView? =
            if (v is BoardSurfaceView) {
                v
            } else {
                (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            }
        return find(host!!.rootView)
    }

    private fun rectOf(tag: String): Rect {
        val d = compose.density.density
        val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return Rect((b.left.value * d).toInt(), (b.top.value * d).toInt(), (b.right.value * d).toInt(), (b.bottom.value * d).toInt())
    }

    private fun holds() = input.log.count { it == "hold" }

    private fun releases() = input.log.count { it == "release" }

    private fun outstanding(): Int = holds() - releases()

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

    private fun openPanel() {
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
    }

    /** The harness traverses the view tree only on a layout request: ask for one so the refresher's wait for the next draw ends. */
    private fun drawFrames() {
        compose.waitForIdle()
        compose.runOnUiThread { host!!.rootView.requestLayout() }
        compose.waitForIdle()
    }

    private fun openPenPopup() {
        compose.onNodeWithTag(stripTag("pen")).performClick()
        drawFrames()
    }

    private fun commitStroke() {
        strokes.trySend(StrokeEvent.Committed(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))))
    }

    private fun advanceAfterStroke(ms: Long) {
        compose.mainClock.autoAdvance = false
        commitStroke()
        compose.mainClock.advanceTimeBy(ms)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
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
                    it.x = 300f
                    it.y = 600f
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

    /** The hold is balanced and raw drawing never ran with an empty exclude list. */
    private fun assertBalancedAndCovered() {
        assertThat(outstanding()).isEqualTo(0)
        assertThat(emptyStartsWhileEnabled()).isEmpty()
    }

    @Test
    fun open_panel_then_open_popup_then_tap_board() {
        show()
        openPanel()
        openPenPopup()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertIsDisplayed()
        compose.onNodeWithTag(TAG_POPUP).assertIsDisplayed()
        assertThat(lastStart()).contains(rectOf(TAG_POPUP).toString())
        assertThat(lastStart()).contains(rectOf(TAG_PANEL_HOST).toString())
        input.log.clear()

        fingerTap()
        drawFrames()

        compose.onNodeWithTag(TAG_POPUP).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExist()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertDoesNotExist()
        val want = lastStart()
        assertThat(want).contains(rectOf(TAG_INDICATOR).toString())
        assertThat(want).contains(rectOf(TAG_PANEL_INDICATOR).toString())
        assertThat(input.log.first()).isEqualTo("hold")
        assertThat(enabledAt()).isGreaterThan(input.log.lastIndexOf(want))
        assertBalancedAndCovered()
    }

    @Test
    fun popup_open_then_rotate() {
        show()
        openPanel()
        openPenPopup()
        input.log.clear()

        // The activity handles the change itself: switch the layout size and report the new configuration.
        compose.runOnUiThread {
            size.value = DpSize(900.dp, 600.dp)
            val activity = host!!.context as androidx.activity.ComponentActivity
            val config = android.content.res.Configuration(activity.resources.configuration)
            config.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
            config.screenWidthDp = 900
            config.screenHeightDp = 600
            activity.onConfigurationChanged(config)
        }
        drawFrames()

        compose.onNodeWithTag(TAG_POPUP).assertDoesNotExist()
        val d = compose.density.density
        val bounds = Rect(0, 0, (900 * d).toInt(), (600 * d).toInt())
        for (tag in listOf(TAG_DOCK, TAG_PANEL_HOST)) {
            val r = rectOf(tag)
            assertThat(bounds.contains(r)).isTrue()
            assertThat(lastStart()).contains(r.toString())
        }
        assertThat(input.limits.last()).isEqualTo(bounds)
        assertBalancedAndCovered()
    }

    @Test
    fun panel_open_then_stroke_commit_hides_all_after_600ms() {
        show()
        openPanel()
        input.log.clear()

        advanceAfterStroke(ShellState.AUTO_HIDE_AFTER_STROKE_MS - 100)
        assertThat(state.panelVisible).isTrue()
        assertThat(state.chromeVisible).isTrue()
        assertThat(input.log).isEmpty()

        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(ShellState.AUTO_HIDE_AFTER_STROKE_MS).isEqualTo(600L)
        assertThat(state.panelVisible).isFalse()
        assertThat(state.chromeVisible).isFalse()
        assertThat(lastStart()).contains(rectOf(TAG_INDICATOR).toString())
        assertThat(lastStart()).contains(rectOf(TAG_PANEL_INDICATOR).toString())
        assertBalancedAndCovered()
    }

    @Test
    fun rapid_show_hide_show_of_panel_leaves_hold_balanced() {
        show()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        fingerTap()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        drawFrames()

        assertThat(state.panelVisible).isTrue()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertIsDisplayed()
        assertThat(lastStart()).contains(rectOf(TAG_PANEL_HOST).toString())
        assertBalancedAndCovered()
    }
}
