package io.github.szymonrychu.booxsketch.board

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardToolbarTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private fun vm() =
        BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun send_tap_and_long_press_pass_all_flag() {
        val calls = mutableListOf<Pair<Boolean, String?>>()
        compose.setContent { BoardToolbar(vm(), onSend = { all, caption -> calls += all to caption }) }
        compose.onNodeWithTag(TAG_SEND).performClick()
        compose.onNodeWithTag(TAG_SEND).performTouchInput { longClick() }
        compose.onNodeWithText(SEND_ALL_LABEL).performClick()
        assertThat(calls).containsExactly(false to null, true to null).inOrder()
    }

    @Test
    fun page_strip_adds_navigates_and_deletes() {
        compose.setContent { BoardToolbar(vm()) }
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 1 of 1")
        compose.onNodeWithText("Add page").performClick()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 2 of 2")
        compose.onNodeWithText("Previous").performClick()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 1 of 2")
        compose.onNodeWithText("Delete page").performClick()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertTextEquals("page 1 of 1")
    }

    @Test
    fun every_palette_colour_is_a_48dp_swatch_and_tapping_one_selects_it() {
        val vm = vm()
        val changes = mutableListOf<Boolean>()
        val ui =
            object : UiRefresh {
                override fun uiChanged(settled: Boolean) {
                    changes += settled
                }
            }
        compose.setContent { BoardToolbar(vm, ui = ui) }
        for (c in PenColor.entries) {
            compose.onNodeWithTag(colorTag(c)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag(colorTag(PenColor.BLACK)).assertIsSelected()
        compose
            .onNodeWithTag(colorTag(PenColor.BLUE))
            .assertIsNotSelected()
            .performScrollTo()
            .performClick()
        assertThat(vm.color.value).isEqualTo(PenColor.BLUE)
        compose.onNodeWithTag(colorTag(PenColor.BLUE)).assertIsSelected()
        compose.onNodeWithTag(colorTag(PenColor.BLACK)).assertIsNotSelected()
        assertThat(changes).containsExactly(true)
    }

    @Test
    fun swatches_are_named_for_accessibility() {
        compose.setContent { BoardToolbar(vm()) }
        compose.onNodeWithContentDescription("Colour red").assertExists()
    }

    @Test
    fun picking_a_colour_leaves_the_eraser() {
        val vm = vm()
        compose.setContent { BoardToolbar(vm) }
        compose.onNodeWithText("Eraser").performClick()
        compose.onNodeWithTag(colorTag(PenColor.GREEN)).performScrollTo().performClick()
        assertThat(vm.eraser.value).isFalse()
        assertThat(vm.color.value).isEqualTo(PenColor.GREEN)
    }

    @Test
    fun rectangle_and_arrow_tools_are_offered_and_selectable() {
        val vm = vm()
        compose.setContent { BoardToolbar(vm) }
        compose.onNodeWithText(Tool.RECTANGLE.wire).performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.RECTANGLE)
        compose.onNodeWithText(Tool.RECTANGLE.wire).assertIsSelected()
        compose.onNodeWithText(Tool.ARROW.wire).performClick()
        assertThat(vm.tool.value).isEqualTo(Tool.ARROW)
        compose.onNodeWithText(Tool.RECTANGLE.wire).assertIsNotSelected()
    }

    @Test
    fun every_tool_chip_is_visible_without_scrolling_and_at_least_48dp_tall() {
        compose.setContent { BoardToolbar(vm()) }
        (Tool.entries.map { it.wire } + "Eraser").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }
}
