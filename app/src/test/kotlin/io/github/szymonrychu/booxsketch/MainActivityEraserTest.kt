package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class MainActivityEraserTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun eraser_chip_reaches_the_touch_input() {
        val container = ApplicationProvider.getApplicationContext<App>().container
        assertThat(container.touchInput.eraserMode).isFalse()
        compose.onNodeWithText("Eraser").performClick()
        compose.waitForIdle()
        assertThat(container.boardViewModel.eraser.value).isTrue()
        assertThat(container.touchInput.eraserMode).isTrue()
        compose.onNodeWithText("Eraser").performClick()
        compose.waitForIdle()
        assertThat(container.touchInput.eraserMode).isFalse()
    }

    @Test
    fun activity_shows_board_and_empty_panel() {
        compose.onNodeWithText("Nothing here yet").assertExists()
        compose.onNodeWithText("Undo").assertExists()
    }
}
