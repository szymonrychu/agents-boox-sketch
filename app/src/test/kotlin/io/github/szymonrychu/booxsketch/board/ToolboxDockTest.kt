package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class DockLog(
    private val log: MutableList<String>,
) : UiRefresh {
    override fun uiChanged(settled: Boolean) {
        log += "ui"
    }

    override fun chromeChanged() {
        log += "chrome"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class ToolboxDockTest {
    @get:Rule val compose = createComposeRule()

    private val dock = Rect(0, 0, 100, 900)
    private val indicator = Rect(0, 400, 60, 460)

    private class Fixture {
        val strip = mutableListOf<List<Rect>>()
        val popup = mutableListOf<List<Rect>>()
    }

    @Suppress("LongParameterList")
    private fun show(
        visible: Boolean = true,
        popup: ToolPopup? = null,
        anchorY: Float = 0f,
        size: Modifier = Modifier.fillMaxSize(),
        remembered: IntSize? = null,
        ui: UiRefresh = UiRefresh.None,
        onShow: () -> Unit = {},
        fixture: Fixture = Fixture(),
    ): Fixture {
        compose.setContent {
            Box(size) {
                ToolboxDock(
                    visible,
                    onShow,
                    ui,
                    { fixture.strip += it },
                    { fixture.popup += it },
                    remembered,
                    {},
                    popup,
                    anchorY,
                    strip = { Box(Modifier.size(STRIP_WIDTH, 100.dp)) { Text("tools") } },
                    popupContent = { Box(Modifier.size(120.dp, 80.dp)) },
                )
            }
        }
        compose.waitForIdle()
        return fixture
    }

    @Test
    fun dock_exclude_rects_visible_uses_dock() {
        assertThat(dockExcludeRects(true, dock, indicator)).containsExactly(dock)
    }

    @Test
    fun dock_exclude_rects_hidden_uses_indicator() {
        assertThat(dockExcludeRects(false, dock, indicator)).containsExactly(indicator)
    }

    @Test
    fun dock_exclude_rects_null_rect_is_empty() {
        assertThat(dockExcludeRects(true, null, indicator)).isEmpty()
        assertThat(dockExcludeRects(false, dock, null)).isEmpty()
    }

    @Test
    fun strip_is_on_the_left_edge_full_height_inside_insets() {
        show()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag(TAG_DOCK).getUnclippedBoundsInRoot()
        assertThat(b.left).isEqualTo(root.left)
        assertThat(b.top).isEqualTo(root.top)
        assertThat(b.bottom).isEqualTo(root.bottom)
        assertThat(b.right - b.left).isEqualTo(STRIP_WIDTH)
    }

    @Test
    fun indicator_is_on_the_left_edge_when_hidden() {
        show(visible = false)
        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExist()
        val node = compose.onNodeWithTag(TAG_INDICATOR)
        node.assertWidthIsEqualTo(INDICATOR_TARGET).assertHeightIsEqualTo(INDICATOR_TARGET)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val b = node.getUnclippedBoundsInRoot()
        assertThat(b.left).isEqualTo(root.left)
        assertThat((b.top + b.bottom) / 2).isEqualTo((root.top + root.bottom) / 2)
    }

    @Test
    fun indicator_shown_only_when_hidden() {
        var visible by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ToolboxDock(visible, {}, UiRefresh.None, {}, {}, null, {}, null, 0f, strip = { Text("tools") }, popupContent = {})
            }
        }
        compose.onNodeWithTag(TAG_INDICATOR).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOCK).assertIsDisplayed()
        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_INDICATOR).assertIsDisplayed()
        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExist()
    }

    @Test
    fun indicator_tap_calls_chrome_changed_then_on_show() {
        val log = mutableListOf<String>()
        show(visible = false, ui = DockLog(log), onShow = { log += "show" })
        compose.onNodeWithTag(TAG_INDICATOR).performClick()
        assertThat(log).containsExactly("chrome", "show").inOrder()
    }

    @Test
    fun strip_slot_rendered_inside_dock() {
        show()
        compose.onNode(hasText("tools") and hasAnyAncestor(hasTestTag(TAG_DOCK))).assertExists()
    }

    @Test
    fun popup_is_to_the_right_of_the_strip_at_the_anchor_height() {
        val d = compose.density.density
        show(popup = ToolPopup.PEN, anchorY = 200f * d)
        val strip = compose.onNodeWithTag(TAG_DOCK).getUnclippedBoundsInRoot()
        val popup = compose.onNodeWithTag(TAG_POPUP).getUnclippedBoundsInRoot()
        assertThat(popup.left).isEqualTo(strip.right)
        assertThat(popup.top.value).isWithin(1f).of(200f)
    }

    @Test
    fun popup_is_clamped_inside_the_parent() {
        val d = compose.density.density
        show(popup = ToolPopup.PEN, anchorY = 10_000f * d)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val popup = compose.onNodeWithTag(TAG_POPUP).getUnclippedBoundsInRoot()
        assertThat(popup.bottom.value).isWithin(1f).of(root.bottom.value)
    }

    @Test
    fun excludes_are_strip_plus_popup() {
        val f = show(popup = ToolPopup.PEN, anchorY = 100f)
        val d = compose.density.density
        val strip = f.strip.last().single()
        val popup = f.popup.last().single()
        assertThat(strip.width().toFloat()).isWithin(1f).of(56 * d)
        assertThat(popup.left).isEqualTo(strip.right)
        assertThat(popup.width().toFloat()).isWithin(1f).of(120 * d)
    }

    @Test
    fun a_closed_popup_reports_no_rect() {
        val f = show(popup = null)
        assertThat(f.popup.last()).isEmpty()
    }

    @Test
    fun excludes_are_indicator_when_hidden() {
        val f = show(visible = false, popup = null)
        val d = compose.density.density
        val last = f.strip.last().single()
        assertThat(last.width().toFloat()).isWithin(1f).of(48 * d)
        assertThat(last.height().toFloat()).isWithin(1f).of(48 * d)
        assertThat(last.left).isEqualTo(0)
        assertThat(f.popup.last()).isEmpty()
    }

    @Test
    fun hidden_dock_never_reports_a_popup_rect() {
        val f = show(visible = false, popup = ToolPopup.PEN)
        assertThat(f.popup.last()).isEmpty()
        compose.onNodeWithTag(TAG_POPUP).assertDoesNotExist()
    }

    @Test
    fun show_reports_remembered_strip_rect_in_first_composition() {
        var visible by mutableStateOf(false)
        val reports = mutableListOf<List<Rect>>()
        compose.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                ToolboxDock(visible, {}, UiRefresh.None, { reports += it }, {}, IntSize(150, 100), {}, null, 0f, strip = {
                    Box(Modifier.fillMaxHeight().size(56.dp, 20.dp))
                }, popupContent = {})
            }
        }
        compose.waitForIdle()
        reports.clear()
        visible = true
        compose.waitForIdle()
        val first = reports.first().single()
        val d = compose.density.density
        assertThat(first.left).isEqualTo(0)
        assertThat(first.width()).isEqualTo(150)
        assertThat(first.top).isEqualTo(0)
        assertThat(first.bottom).isEqualTo((300 * d).toInt())
    }
}
