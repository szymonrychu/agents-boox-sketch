package io.github.szymonrychu.booxsketch.board

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
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
}
