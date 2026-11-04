package io.github.szymonrychu.booxsketch.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class PanelShellTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun reset() = PanelTabs.clearForTest()

    @After
    fun cleanup() = PanelTabs.clearForTest()

    @Test
    fun registered_tabs_shown_in_order() {
        PanelTabs.register(PanelTab("thread", "Thread", 20) { Text("thread body") })
        PanelTabs.register(PanelTab("spec", "Spec", 10) { Text("spec body") })
        assertThat(PanelTabs.tabs.map { it.id }).containsExactly("spec", "thread").inOrder()
        compose.setContent { PanelShell() }
        compose.onNodeWithText("spec body").assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAB_PREFIX + "thread").performClick()
        compose.onNodeWithText("thread body").assertIsDisplayed()
        compose.onNodeWithText("Spec").assertIsDisplayed()
    }

    @Test
    fun no_tabs_shows_empty_state() {
        compose.setContent { PanelShell() }
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun conversation_button_calls_the_hook() {
        var opened = 0
        PanelTabs.onOpenConversations = { opened++ }
        compose.setContent { PanelShell() }
        compose.onNodeWithTag(TAG_CONVERSATION).performClick()
        assertThat(opened).isEqualTo(1)
    }
}
