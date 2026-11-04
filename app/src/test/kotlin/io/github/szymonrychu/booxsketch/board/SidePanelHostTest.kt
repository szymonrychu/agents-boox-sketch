package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class PanelLogUi(
    private val log: MutableList<String>,
) : UiRefresh {
    override fun uiChanged(settled: Boolean) {
        log += "ui"
    }

    override fun chromeChanged() {
        log += "chrome"
    }
}

private const val TAG_PANEL_BODY = "panel_body"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class SidePanelHostTest {
    @get:Rule val compose = createComposeRule()

    private var visible by mutableStateOf(false)
    private val log = mutableListOf<String>()
    private var excludes: List<Rect> = emptyList()

    private fun show() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                SidePanelHost(
                    visible = visible,
                    onShow = {
                        log += "show"
                        visible = true
                    },
                    onHide = {
                        log += "hide"
                        visible = false
                    },
                    ui = PanelLogUi(log),
                    onExcludeChange = { excludes = it },
                ) { Text("panel", Modifier.testTag(TAG_PANEL_BODY)) }
            }
        }
    }

    private fun rectOf(tag: String): Rect {
        val d = compose.density.density
        val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return Rect((b.left.value * d).toInt(), (b.top.value * d).toInt(), (b.right.value * d).toInt(), (b.bottom.value * d).toInt())
    }

    @Test
    fun hidden_by_default_shows_indicator_only() {
        show()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertDoesNotExist()
        compose.onNodeWithTag(TAG_PANEL_BODY).assertDoesNotExist()
        val node = compose.onNodeWithTag(TAG_PANEL_INDICATOR)
        node.assertWidthIsEqualTo(INDICATOR_TARGET).assertHeightIsEqualTo(INDICATOR_TARGET)
        // Right edge, vertically centred.
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val b = node.getUnclippedBoundsInRoot()
        assertThat(b.right).isEqualTo(root.right)
        assertThat((b.top + b.bottom) / 2).isEqualTo((root.top + root.bottom) / 2)
    }

    @Test
    fun indicator_tap_shows_panel_and_hide_button_hides() {
        show()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PANEL_BODY).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).assertDoesNotExist()
        assertThat(log).containsExactly("chrome", "show").inOrder()

        compose.onNodeWithTag(TAG_PANEL_HIDE).performClick()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertDoesNotExist()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).assertIsDisplayed()
        assertThat(log).containsExactly("chrome", "show", "chrome", "hide").inOrder()
    }

    @Test
    fun exclude_rect_is_indicator_when_hidden_and_panel_when_shown() {
        show()
        compose.waitForIdle()
        assertThat(excludes).containsExactly(rectOf(TAG_PANEL_INDICATOR))

        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
        assertThat(excludes).containsExactly(rectOf(TAG_PANEL_HOST))
    }

    @Test
    fun landscape_panel_is_45_percent_wide_full_height_on_the_right() {
        visible = true
        show()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag(TAG_PANEL_HOST).getUnclippedBoundsInRoot()
        assertThat(b.right).isEqualTo(root.right)
        assertThat(b.top).isEqualTo(root.top)
        assertThat(b.bottom).isEqualTo(root.bottom)
        assertThat((b.right - b.left).value).isWithin(1f).of((root.right - root.left).value * PANEL_WIDE_FRACTION)
    }

    @Test
    @Config(qualifiers = "w930dp-h1240dp-port-xhdpi")
    fun portrait_panel_is_on_the_right_not_the_bottom() {
        visible = true
        show()
        val root = rootRect()
        val panel = rectOf(TAG_PANEL_HOST)
        // Full height at the right edge (never a strip below the board), 80 percent of the width in portrait.
        assertThat(panel.right).isEqualTo(root.right)
        assertThat(panel.top).isEqualTo(0)
        assertThat(panel.bottom).isEqualTo(root.bottom)
        assertThat(panel.left).isGreaterThan(0)
        assertThat(panel.width().toFloat()).isWithin(2f).of(root.width() * PANEL_TALL_FRACTION)
    }

    private fun rootRect(): Rect {
        val d = compose.density.density
        val b = compose.onRoot().getUnclippedBoundsInRoot()
        return Rect((b.left.value * d).toInt(), (b.top.value * d).toInt(), (b.right.value * d).toInt(), (b.bottom.value * d).toInt())
    }
}
