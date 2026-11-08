package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Records every refresh call: "ui" (settled), "ui-unsettled" and "chrome". */
internal class CallLog : UiRefresh {
    val calls = mutableListOf<String>()

    override fun uiChanged(settled: Boolean) {
        calls += if (settled) "ui" else "ui-unsettled"
    }

    override fun chromeChanged() {
        calls += "chrome"
    }
}

private val STRIP_NAMES = listOf("pen", "eraser", "box", "picture", "arrow", "colour", "undo", "redo", "pages", "send", "hide")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h900dp-xhdpi")
class ToolStripTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val ui = CallLog()
    private val state = ToolboxState()

    private fun vm() =
        BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    /** The strip with the open popup (if any) beside it, as the dock places them. */
    private fun show(
        vm: BoardViewModel = vm(),
        options: ToolbarOptions = ToolbarOptions(),
        onHide: () -> Unit = {},
    ) = compose.setContent {
        Row {
            ToolStrip(vm, state, onHide, ui, options = options)
            state.popup?.let { ToolPopupContent(it, vm, state, { _, _ -> }, ui, options = options) }
        }
    }

    @Test
    fun strip_has_all_buttons_and_no_text_chips() {
        show()
        for (n in STRIP_NAMES) compose.onNodeWithTag(stripTag(n)).assertExists()
        for (label in listOf("Eraser", "Undo", "Redo", "Send", "Box", "Picture", "Arrow")) {
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    @Test
    fun first_tap_on_box_selects_without_popup() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("box")).performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.RECTANGLE)
        assertThat(state.popup).isNull()
        compose.onNodeWithTag(stripTag("box")).assertIsSelected()
        compose.onNodeWithTag(stripTag("pen")).assertIsNotSelected()
    }

    @Test
    fun first_tap_on_picture_selects_the_picture_tool_without_popup() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("picture")).performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.IMAGE_REGION)
        assertThat(state.popup).isNull()
        compose.onNodeWithTag(stripTag("picture")).assertIsSelected()
        compose.onNodeWithTag(stripTag("box")).assertIsNotSelected()
    }

    @Test
    fun box_and_picture_are_separate_buttons() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("picture")).performClick()
        compose.onNodeWithTag(stripTag("box")).performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.RECTANGLE)
        compose.onNodeWithTag(stripTag("box")).assertIsSelected()
        compose.onNodeWithTag(stripTag("picture")).assertIsNotSelected()
    }

    @Test
    fun second_tap_opens_shape_popup_with_colour_grid_and_no_slider() {
        show()
        compose.onNodeWithTag(stripTag("box")).performClick()
        compose.onNodeWithTag(stripTag("box")).performClick()
        assertThat(state.popup).isEqualTo(ToolPopup.SHAPE)
        compose.onNodeWithTag(colorTag(PenColor.RED)).assertExists()
        compose.onNodeWithTag(TAG_WIDTH_SLIDER).assertDoesNotExist()
        compose.onNodeWithTag(stripTag("box")).performClick()
        assertThat(state.popup).isNull()
    }

    @Test
    fun tapping_the_other_shape_selects_it_and_closes_the_popup() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("box")).performClick()
        compose.onNodeWithTag(stripTag("box")).performClick()
        compose.onNodeWithTag(stripTag("arrow")).performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.ARROW)
        assertThat(state.popup).isNull()
    }

    @Test
    fun pen_button_after_a_shape_restores_the_last_pen() {
        val vm = vm()
        show(vm)
        vm.setTool(Tool.MARKER)
        compose.waitForIdle()
        compose.onNodeWithTag(stripTag("box")).performClick()
        compose.onNodeWithTag(stripTag("pen")).performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.MARKER)
        compose.onNodeWithTag(stripTag("pen")).assertIsSelected()
    }

    @Test
    fun eraser_selects_then_opens_its_popup_and_a_pen_pick_leaves_it() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("eraser")).performClick()
        assertThat(vm.eraser.value).isTrue()
        compose.onNodeWithTag(stripTag("eraser")).performClick()
        assertThat(state.popup).isEqualTo(ToolPopup.ERASER)
        compose.onNodeWithTag(stripTag("pen")).performClick()
        assertThat(vm.eraser.value).isFalse()
        assertThat(state.popup).isNull()
    }

    @Test
    fun a_tool_switched_elsewhere_closes_the_popup_of_the_tool_left() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("pen")).performClick()
        assertThat(state.popup).isEqualTo(ToolPopup.PEN)
        compose.runOnUiThread { vm.setEraser(true) }
        compose.waitForIdle()
        assertThat(state.popup).isNull()
    }

    @Test
    fun undo_redo_disabled_state_follows_vm() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("undo")).assertIsNotEnabled()
        compose.onNodeWithTag(stripTag("redo")).assertIsNotEnabled()
        compose.runOnUiThread { vm.onStroke(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L), Point(9f, 9f, 0.5f, 5L)))) }
        compose.onNodeWithTag(stripTag("undo")).assertIsEnabled()
        compose.onNodeWithTag(stripTag("undo")).performClick()
        compose.onNodeWithTag(stripTag("undo")).assertIsNotEnabled()
        compose.onNodeWithTag(stripTag("redo")).assertIsEnabled()
    }

    @Test
    fun every_button_is_at_least_48dp() {
        show()
        for (n in STRIP_NAMES) compose.onNodeWithTag(stripTag(n)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun every_state_change_calls_ui_changed() {
        show()
        compose.onNodeWithTag(stripTag("box")).performClick()
        assertThat(ui.calls).containsExactly("ui")
        compose.onNodeWithTag(stripTag("box")).performClick()
        assertThat(ui.calls).containsExactly("ui", "chrome").inOrder()
        compose.onNodeWithTag(stripTag("undo")).performClick()
        assertThat(ui.calls).containsExactly("ui", "chrome").inOrder()
    }

    @Test
    fun undo_and_redo_clicks_report_when_enabled() {
        val vm = vm()
        compose.runOnUiThread { vm.onStroke(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L), Point(9f, 9f, 0.5f, 5L)))) }
        show(vm)
        compose.onNodeWithTag(stripTag("undo")).performClick()
        compose.onNodeWithTag(stripTag("redo")).performClick()
        assertThat(ui.calls).containsExactly("ui", "ui").inOrder()
    }

    @Test
    fun colour_pages_and_send_open_their_popup_on_the_first_tap() {
        show()
        for ((name, popup) in listOf("colour" to ToolPopup.COLOUR, "pages" to ToolPopup.PAGES, "send" to ToolPopup.SEND)) {
            compose.onNodeWithTag(stripTag(name)).performClick()
            assertThat(state.popup).isEqualTo(popup)
        }
        assertThat(ui.calls).containsExactly("chrome", "chrome", "chrome")
    }

    @Test
    fun pages_button_shows_the_page_counter() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(stripTag("pages")).assertExists()
        compose.onNodeWithText("1/1").assertExists()
        compose.runOnUiThread { vm.addPage() }
        compose.onNodeWithText("2/2").assertExists()
    }

    @Test
    fun document_options_hide_the_pages_button_and_rename_send() {
        show(options = ToolbarOptions(pageControls = false, sendLabel = "Send notes"))
        compose.onNodeWithTag(stripTag("pages")).assertDoesNotExist()
        compose.onNodeWithContentDescription("Send notes").assertExists()
    }

    @Test
    fun hide_tap_asks_for_a_full_refresh_then_hides() {
        val log = mutableListOf<String>()
        val ui2 = this.ui
        show(onHide = { log += "hide:${ui2.calls}" })
        compose.onNodeWithTag(stripTag("hide")).performClick()
        assertThat(log).containsExactly("hide:[chrome]")
    }

    @Test
    fun pen_button_is_named_after_the_selected_pen() {
        val vm = vm()
        show(vm)
        compose.onNodeWithContentDescription(Tool.BALLPOINT.wire).assertExists()
        compose.runOnUiThread { vm.setTool(Tool.BRUSH) }
        compose.onNodeWithContentDescription(Tool.BRUSH.wire).assertExists()
    }
}
