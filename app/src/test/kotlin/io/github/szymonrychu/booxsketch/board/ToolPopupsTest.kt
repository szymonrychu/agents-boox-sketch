package io.github.szymonrychu.booxsketch.board

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h900dp-xhdpi")
class ToolPopupsTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val ui = CallLog()
    private val state = ToolboxState()
    private val sends = mutableListOf<Pair<Boolean, String?>>()

    private fun vm() =
        BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun show(
        kind: ToolPopup,
        vm: BoardViewModel = vm(),
        options: ToolbarOptions = ToolbarOptions(),
    ) = compose.setContent { ToolPopupContent(kind, vm, state, { all, c -> sends += all to c }, ui, options = options) }

    @Test
    fun pen_popup_changes_type_and_width() {
        val vm = vm()
        show(ToolPopup.PEN, vm)
        compose.onNodeWithTag(penTag(Tool.BALLPOINT)).assertIsSelected()
        compose.onNodeWithTag(penTag(Tool.MARKER)).assertIsNotSelected().performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.MARKER)
        compose.onNodeWithTag(penTag(Tool.MARKER)).assertIsSelected()
        compose.onNodeWithTag(TAG_WIDTH_SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        assertThat(vm.width.value).isEqualTo(10f)
    }

    @Test
    fun every_pen_type_is_offered_as_a_48dp_button() {
        show(ToolPopup.PEN)
        for (t in Tool.entries.filter { !it.isShape }) {
            compose.onNodeWithTag(penTag(t)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun width_drag_reports_unsettled_then_settled() {
        show(ToolPopup.PEN)
        compose.onNodeWithTag(TAG_WIDTH_SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        assertThat(ui.calls).containsExactly("ui-unsettled", "ui").inOrder()
    }

    @Test
    fun colour_popup_selects_colour_and_marks_it() {
        val vm = vm()
        show(ToolPopup.COLOUR, vm)
        for (c in PenColor.entries) {
            compose.onNodeWithTag(colorTag(c)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag(colorTag(PenColor.BLACK)).assertIsSelected()
        compose.onNodeWithTag(colorTag(PenColor.BLUE)).assertIsNotSelected().performClick()
        assertThat(vm.color.value).isEqualTo(PenColor.BLUE)
        compose.onNodeWithTag(colorTag(PenColor.BLUE)).assertIsSelected()
        compose.onNodeWithTag(colorTag(PenColor.BLACK)).assertIsNotSelected()
        assertThat(ui.calls).containsExactly("ui")
    }

    @Test
    fun picking_a_colour_leaves_the_eraser_but_keeps_the_tool() {
        val vm = vm()
        vm.setTool(Tool.CHARCOAL)
        vm.setEraser(true)
        show(ToolPopup.COLOUR, vm)
        compose.onNodeWithTag(colorTag(PenColor.GREEN)).performClick()
        assertThat(vm.eraser.value).isFalse()
        assertThat(vm.tool.value).isEqualTo(Tool.CHARCOAL)
    }

    @Test
    fun shape_selected_pen_slider_value_unchanged() {
        val vm = vm()
        vm.setWidth(11f)
        vm.setTool(Tool.RECTANGLE)
        show(ToolPopup.SHAPE, vm)
        compose.onNodeWithTag(TAG_WIDTH_SLIDER).assertDoesNotExist()
        compose.onNodeWithTag(colorTag(PenColor.RED)).performClick()
        assertThat(vm.width.value).isEqualTo(11f)
        assertThat(vm.effectiveWidth.value).isEqualTo(BoardViewModel.SHAPE_WIDTH)
    }

    @Test
    fun eraser_popup_clears_the_page_undoably() {
        val vm = vm()
        vm.onStroke(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L), Point(9f, 9f, 0.5f, 5L))))
        show(ToolPopup.ERASER, vm)
        compose.onNodeWithText("Clear page").performClick()
        assertThat(
            vm.notebook.value.pages[0]
                .strokes,
        ).isEmpty()
        vm.undo()
        assertThat(
            vm.notebook.value.pages[0]
                .strokes,
        ).hasSize(1)
        assertThat(ui.calls).containsExactly("ui")
    }

    @Test
    fun pages_popup_navigates() {
        show(ToolPopup.PAGES)
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 1 of 1")
        compose.onNodeWithText("Add page").performClick()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 2 of 2")
        compose.onNodeWithText("Previous").performClick()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 1 of 2")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 2 of 2")
        compose.onNodeWithText("Delete page").performClick()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 1 of 1")
        assertThat(ui.calls).hasSize(4)
    }

    @Test
    fun send_popup_passes_caption_and_all_flag_and_closes() {
        state.tap(ToolPopup.SEND, alreadySelected = false)
        show(ToolPopup.SEND)
        compose.onNodeWithTag(TAG_SEND).performClick()
        assertThat(sends).containsExactly(false to null)
        assertThat(state.popup).isNull()
        state.tap(ToolPopup.SEND, alreadySelected = false)
        compose.onNodeWithText("Caption").performTextInput("hello")
        compose.onNodeWithTag(TAG_SEND_ALL).performClick()
        compose.onNodeWithTag(TAG_SEND).performTouchInput { longClick() }
        assertThat(sends).containsExactly(false to null, true to "hello", true to "hello").inOrder()
    }

    @Test
    fun caption_typing_reports_unsettled() {
        show(ToolPopup.SEND)
        compose.onNodeWithText("Caption").performTextInput("ab")
        assertThat(ui.calls).isNotEmpty()
        assertThat(ui.calls.toSet()).containsExactly("ui-unsettled")
        assertThat(state.caption).isEqualTo("ab")
    }

    @Test
    fun document_send_popup_has_one_button_and_its_own_label() {
        show(ToolPopup.SEND, options = ToolbarOptions(pageControls = false, sendLabel = "Send notes"))
        compose.onNodeWithText("Send notes").assertExists()
        compose.onNodeWithTag(TAG_SEND_ALL).assertDoesNotExist()
        compose.onNodeWithTag(TAG_SEND).performTouchInput { longClick() }
        assertThat(sends).isEmpty()
    }

    @Test
    fun buttons_in_every_popup_are_at_least_48dp() {
        show(ToolPopup.PAGES)
        for (label in listOf("Previous", "Next", "Add page", "Delete page")) {
            compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp)
        }
    }
}
