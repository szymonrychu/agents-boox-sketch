package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
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
private const val PORTRAIT = "w930dp-h1240dp-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = LANDSCAPE)
class ToolStripScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private fun vm() =
        BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun capture(
        name: String,
        popup: ToolPopup?,
        prepare: (BoardViewModel) -> Unit = {},
    ) {
        val vm = vm().also(prepare)
        val state = ToolboxState()
        popup?.let { state.tap(it, alreadySelected = true) }
        compose.setContent {
            MaterialTheme {
                Row(Modifier.fillMaxSize().background(Color.White)) {
                    ToolStrip(vm, state, {}, UiRefresh.None)
                    state.popup?.let { ToolPopupContent(it, vm, state, { _, _ -> }, UiRefresh.None, Modifier.align(Alignment.Top)) }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun strip_closed() = capture("board_strip_closed", null)

    @Test
    fun strip_pen_popup() = capture("board_strip_pen_popup", ToolPopup.PEN) { it.setTool(Tool.FOUNTAIN) }

    @Test
    fun strip_colour_popup() = capture("board_strip_colour_popup", ToolPopup.COLOUR) { it.setColor(PenColor.RED) }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun strip_closed_portrait() = capture("board_strip_closed_portrait", null)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun strip_pen_popup_portrait() = capture("board_strip_pen_popup_portrait", ToolPopup.PEN) { it.setTool(Tool.FOUNTAIN) }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun strip_colour_popup_portrait() = capture("board_strip_colour_popup_portrait", ToolPopup.COLOUR) { it.setColor(PenColor.RED) }
}
