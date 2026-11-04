package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import io.github.szymonrychu.booxsketch.ui.AppShell
import io.github.szymonrychu.booxsketch.ui.BOARD_TAB_ID
import io.github.szymonrychu.booxsketch.ui.ShellState
import io.github.szymonrychu.booxsketch.ui.TabRegistry
import io.github.szymonrychu.booxsketch.ui.TabSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val LANDSCAPE = "w1240dp-h930dp-land-xhdpi"
private const val PORTRAIT = "w930dp-h1240dp-port-xhdpi"

/** The whole board with the shell: strip, popups, side panel and the hidden-state indicators, on the Note Air 6C. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = LANDSCAPE)
class BoardOverlaysScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private fun show() {
        val registry = TabRegistry()
        registry.register(TabSpec(BOARD_TAB_ID, "Drawings", 10) { })
        registry.register(TabSpec("spec", "Spec", 20) { })
        val vm = BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        compose.setContent {
            MaterialTheme {
                AppShell(
                    tabs = registry,
                    board = { chrome -> BoardScreen(vm, FreezingInput(), chrome = chrome, panel = { Text("Spec panel") }) },
                    state = ShellState(),
                    insets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun closed(name: String) {
        show()
        capture(name)
    }

    private fun penPopup(name: String) {
        show()
        compose.onNodeWithTag(stripTag("pen")).performClick()
        capture(name)
    }

    private fun colourPopup(name: String) {
        show()
        compose.onNodeWithTag(stripTag("colour")).performClick()
        capture(name)
    }

    private fun panelOpen(name: String) {
        show()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        capture(name)
    }

    private fun hidden(name: String) {
        show()
        compose.onNodeWithTag(TAG_HIDE).performClick()
        capture(name)
    }

    @Test
    fun overlays_closed_landscape() = closed("board_overlays_closed")

    @Test
    fun overlays_pen_popup_landscape() = penPopup("board_overlays_pen_popup")

    @Test
    fun overlays_colour_popup_landscape() = colourPopup("board_overlays_colour_popup")

    @Test
    fun overlays_panel_open_landscape() = panelOpen("board_overlays_panel_open")

    @Test
    fun overlays_hidden_two_indicators_landscape() = hidden("board_overlays_hidden")

    @Test
    @Config(qualifiers = PORTRAIT)
    fun overlays_closed_portrait() = closed("board_overlays_closed_portrait")

    @Test
    @Config(qualifiers = PORTRAIT)
    fun overlays_pen_popup_portrait() = penPopup("board_overlays_pen_popup_portrait")

    @Test
    @Config(qualifiers = PORTRAIT)
    fun overlays_colour_popup_portrait() = colourPopup("board_overlays_colour_popup_portrait")

    @Test
    @Config(qualifiers = PORTRAIT)
    fun overlays_panel_open_portrait() = panelOpen("board_overlays_panel_open_portrait")

    @Test
    @Config(qualifiers = PORTRAIT)
    fun overlays_hidden_two_indicators_portrait() = hidden("board_overlays_hidden_portrait")
}
