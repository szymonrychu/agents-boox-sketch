package io.github.szymonrychu.booxsketch

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.board.BoardSurfaceView
import io.github.szymonrychu.booxsketch.board.TAG_DOCK
import io.github.szymonrychu.booxsketch.board.TAG_PANEL_HOST
import io.github.szymonrychu.booxsketch.board.TAG_PANEL_INDICATOR
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Rotation is handled with configChanges: the activity is kept, the panel and the dock are laid
 * out again inside the new screen and the input restarts with the new limit and excludes, all
 * while raw drawing is held off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FreezingInputApp::class, sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class MainActivityRotationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var controller: ActivityController<MainActivity>
    private val input get() = FreezingInputApp.input

    @After
    fun close() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun launch() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitFor(TAG_DOCK)
        // Robolectric creates no real surface: report it so the board starts its input.
        compose.runOnUiThread { surface().let { it.surfaceCreated(it.holder) } }
        compose.waitForIdle()
    }

    private fun rotate() {
        RuntimeEnvironment.setQualifiers("+w1240dp-h930dp-land-xhdpi")
        controller.configurationChange()
        compose.waitForIdle()
    }

    private fun surface(): BoardSurfaceView {
        fun find(v: View): BoardSurfaceView? =
            if (v is BoardSurfaceView) {
                v
            } else {
                (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            }
        return find(controller.get().window.decorView)!!
    }

    private fun px(tag: String?): Rect {
        val d =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .layoutInfo.density.density
        val b = if (tag == null) compose.onRoot().getUnclippedBoundsInRoot() else compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return Rect((b.left.value * d).toInt(), (b.top.value * d).toInt(), (b.right.value * d).toInt(), (b.bottom.value * d).toInt())
    }

    private fun inside(
        r: Rect,
        root: Rect,
    ) = r.left >= root.left && r.top >= root.top && r.right <= root.right && r.bottom <= root.bottom

    private fun holds() = input.log.count { it == "hold" }

    private fun releases() = input.log.count { it == "release" }

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

    private fun openPanel() {
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
    }

    @Test
    fun portrait_to_landscape_panel_is_right_edge_and_inside_screen() {
        launch()
        openPanel()
        rotate()

        val root = px(null)
        assertThat(root.width()).isGreaterThan(root.height())
        val panel = px(TAG_PANEL_HOST)
        assertThat(panel.right).isAtMost(root.right)
        assertThat(panel.left).isGreaterThan(root.width() / 2 - 1)
        assertThat(inside(panel, root)).isTrue()
        assertThat(inside(px(TAG_DOCK), root)).isTrue()
    }

    @Test
    fun rotation_does_not_recreate_the_activity() {
        launch()
        val before = controller.get()
        rotate()
        assertThat(controller.get()).isSameInstanceAs(before)
        assertThat(controller.get().lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun rotation_restarts_input_with_new_limit_and_excludes() {
        launch()
        openPanel()
        input.log.clear()
        input.limits.clear()
        rotate()

        val root = px(null)
        val panel = px(TAG_PANEL_HOST)
        assertThat(input.limits.last()).isEqualTo(Rect(0, 0, surface().width, surface().height))
        assertThat(surface().width).isGreaterThan(surface().height)
        assertThat(surface().width).isEqualTo(root.width())
        assertThat(input.log.last { it.startsWith("start:") }).contains(panel.toString())
    }

    @Test
    fun rotation_leaves_no_outstanding_hold() {
        launch()
        openPanel()
        input.log.clear()
        rotate()

        assertThat(holds()).isAtLeast(1)
        assertThat(releases()).isEqualTo(holds())
        assertThat(enabledAt()).isGreaterThan(input.log.indexOfLast { it.startsWith("start:") })
    }

    @Test
    fun rotation_holds_raw_drawing_before_the_input_restarts() {
        launch()
        openPanel()
        input.log.clear()
        rotate()

        // The hold comes from the configuration change itself, before the view tree is laid out again:
        // no restart with the old layout's rects while raw drawing is on.
        assertThat(input.log.first()).isEqualTo("hold")
        val enabled = enabledAt()
        assertThat(input.log.withIndex().filter { (i, e) -> e.startsWith("start:") && i > enabled }).isEmpty()
    }

    @Test
    fun rotation_with_panel_hidden_keeps_indicator_inside_screen() {
        launch()
        rotate()

        val root = px(null)
        val indicator = px(TAG_PANEL_INDICATOR)
        assertThat(inside(indicator, root)).isTrue()
        assertThat(indicator.right).isEqualTo(root.right)
        assertThat(input.log.last { it.startsWith("start:") }).contains(indicator.toString())
    }
}
