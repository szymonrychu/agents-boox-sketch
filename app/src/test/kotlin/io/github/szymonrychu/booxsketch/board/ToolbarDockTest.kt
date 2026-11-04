package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

private class LogUi(
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
class ToolbarDockTest {
    @get:Rule val compose = createComposeRule()

    private val dock = Rect(0, 800, 1000, 900)
    private val indicator = Rect(900, 850, 960, 910)

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
    fun hide_tap_calls_chrome_changed_then_on_hide() {
        val log = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ToolbarDock(true, { log += "show" }, { log += "hide" }, LogUi(log), {}, null, {}) { Text("tools") }
            }
        }
        compose.onNodeWithTag(TAG_HIDE).performClick()
        assertThat(log).containsExactly("chrome", "hide").inOrder()
    }

    @Test
    fun indicator_shown_only_when_hidden() {
        var visible by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ToolbarDock(visible, {}, {}, UiRefresh.None, {}, null, {}) { Text("tools") }
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
    fun indicator_target_is_48dp_and_bottom_end() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ToolbarDock(false, {}, {}, UiRefresh.None, {}, null, {}) { Text("tools") }
            }
        }
        val node = compose.onNodeWithTag(TAG_INDICATOR)
        node.assertWidthIsEqualTo(INDICATOR_TARGET).assertHeightIsEqualTo(INDICATOR_TARGET)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val b = node.getUnclippedBoundsInRoot()
        assertThat(b.right).isEqualTo(root.right - INDICATOR_MARGIN)
        assertThat(b.bottom).isEqualTo(root.bottom - INDICATOR_MARGIN)
    }

    @Test
    fun indicator_tap_calls_chrome_changed_then_on_show() {
        val log = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ToolbarDock(false, { log += "show" }, { log += "hide" }, LogUi(log), {}, null, {}) { Text("tools") }
            }
        }
        compose.onNodeWithTag(TAG_INDICATOR).performClick()
        assertThat(log).containsExactly("chrome", "show").inOrder()
    }

    @Test
    fun show_reports_remembered_dock_rect_in_first_composition() {
        var visible by mutableStateOf(false)
        val reports = mutableListOf<List<Rect>>()
        compose.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                ToolbarDock(visible, {}, {}, UiRefresh.None, { reports += it }, IntSize(400, 100), {}) {
                    Box(Modifier.height(20.dp))
                }
            }
        }
        compose.waitForIdle()
        reports.clear()
        visible = true
        compose.waitForIdle()
        val first = reports.first()
        assertThat(first).hasSize(1)
        assertThat(first[0].height()).isEqualTo(100)
        val d = compose.density.density
        assertThat(first[0].width()).isEqualTo((400 * d).toInt())
        assertThat(first[0].bottom).isEqualTo((300 * d).toInt())
    }

    @Test
    fun toolbar_slot_rendered_inside_dock() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ToolbarDock(true, {}, {}, UiRefresh.None, {}, null, {}) { Text("tools") }
            }
        }
        compose.onNodeWithTag(TAG_DOCK).assertIsDisplayed()
        compose.onNode(hasText("tools") and hasAnyAncestor(hasTestTag(TAG_DOCK))).assertExists()
    }
}
