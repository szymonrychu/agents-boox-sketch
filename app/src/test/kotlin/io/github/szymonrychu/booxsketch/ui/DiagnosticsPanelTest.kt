package io.github.szymonrychu.booxsketch.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.szymonrychu.booxsketch.ink.InputPath
import io.github.szymonrychu.booxsketch.ink.InputStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onyx_path_shows_raw_drawing_state_and_no_fallback() {
        var raw = true
        compose.setContent { DiagnosticsPanel(InputStatus(InputPath.ONYX_RAW, null), rawEnabled = { raw }) }
        compose.onNodeWithText("Input path: Onyx raw").assertIsDisplayed()
        compose.onNodeWithText("Raw drawing enabled: yes").assertIsDisplayed()
        compose.onNodeWithText("Fallback reason: none").assertIsDisplayed()
        raw = false
        compose.onNodeWithText("Refresh").performClick()
        compose.onNodeWithText("Raw drawing enabled: no").assertIsDisplayed()
    }

    @Test
    fun touch_fallback_shows_the_reason() {
        var status by mutableStateOf(InputStatus(null, null))
        compose.setContent { DiagnosticsPanel(status, rawEnabled = { false }) }
        compose.onNodeWithText("Input path: not selected yet").assertIsDisplayed()
        status = InputStatus(InputPath.TOUCH_FALLBACK, "Onyx input disabled: no raw pen callback within 300 ms")
        compose.onNodeWithText("Input path: Touch fallback").assertIsDisplayed()
        compose.onNodeWithText("Raw drawing enabled: no").assertIsDisplayed()
        compose.onNodeWithText("Fallback reason: Onyx input disabled: no raw pen callback within 300 ms").assertIsDisplayed()
    }
}
