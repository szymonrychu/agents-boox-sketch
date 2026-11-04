package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.board.TAG_PANEL_INDICATOR
import io.github.szymonrychu.booxsketch.board.stripTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PairedApp::class, sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class MainActivityEraserTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun eraser_button_reaches_the_touch_input() {
        val container = ApplicationProvider.getApplicationContext<App>().container
        assertThat(container.touchInput.eraserMode).isFalse()
        compose.onNodeWithTag(stripTag("eraser")).performClick()
        compose.waitForIdle()
        assertThat(container.boardViewModel.eraser.value).isTrue()
        assertThat(container.touchInput.eraserMode).isTrue()
        // A second tap on the selected eraser opens its popup (T-36 Q4); picking the pen leaves the eraser.
        compose.onNodeWithTag(stripTag("pen")).performClick()
        compose.waitForIdle()
        assertThat(container.touchInput.eraserMode).isFalse()
    }

    @Test
    fun activity_shows_board_and_conversations_panel() {
        // The panel is hidden by default since SplitLayout was removed (T-36 Q3): open it first.
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No conversations yet. Send a sketch to start one.").assertExists()
        compose.onNodeWithText("Diagnostics").assertExists()
        compose.onNodeWithTag(stripTag("undo")).assertExists()
    }
}
