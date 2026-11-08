package io.github.szymonrychu.booxsketch.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.InputPath
import io.github.szymonrychu.booxsketch.ink.InputStatus
import io.github.szymonrychu.booxsketch.ink.ShapeDragCounts
import io.github.szymonrychu.booxsketch.onyx.InkExperiments
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

    @Test
    fun shape_drag_events_are_counted_per_source() {
        var counts by mutableStateOf(ShapeDragCounts())
        compose.setContent { DiagnosticsPanel(InputStatus(InputPath.ONYX_RAW, null), rawEnabled = { false }, shapeDrags = counts) }
        compose.onNodeWithText("Shape drag events: window 0, view 0").assertIsDisplayed()
        counts = ShapeDragCounts(window = 12, view = 0)
        compose.onNodeWithText("Shape drag events: window 12, view 0").assertIsDisplayed()
    }

    @Test
    fun pen_redraw_fallbacks_are_counted() {
        var fallbacks by mutableStateOf(0)
        compose.setContent { DiagnosticsPanel(InputStatus(InputPath.ONYX_RAW, null), rawEnabled = { false }, penFallbacks = fallbacks) }
        compose.onNodeWithText("Pen redraw fallbacks: 0").assertIsDisplayed()
        fallbacks = 3
        compose.onNodeWithText("Pen redraw fallbacks: 3").assertIsDisplayed()
    }

    @Test
    fun the_sdk_pens_off_reason_is_shown_next_to_the_fallback_counter() {
        var reason by mutableStateOf<String?>(null)
        compose.setContent {
            DiagnosticsPanel(InputStatus(InputPath.ONYX_RAW, null), rawEnabled = { false }, penFallbacks = 1, penOffReason = reason)
        }
        compose.onNodeWithText("SDK pens off", substring = true).assertDoesNotExist()
        reason = "SDK pens off: java.lang.UnsatisfiedLinkError: no neopen_jni"
        compose.onNodeWithText("SDK pens off: java.lang.UnsatisfiedLinkError: no neopen_jni").assertIsDisplayed()
    }

    @Test
    fun the_firmware_brush_api_status_is_shown() {
        compose.setContent {
            DiagnosticsPanel(
                InputStatus(InputPath.ONYX_RAW, null),
                rawEnabled = { true },
                firmwareBrush = "missing (SDMDevice: setStrokeColor)",
            )
        }
        compose.onNodeWithText("Firmware brush API: missing (SDMDevice: setStrokeColor)").assertIsDisplayed()
    }

    @Test
    fun the_firmware_api_dump_is_shown() {
        compose.setContent {
            DiagnosticsPanel(
                InputStatus(InputPath.ONYX_RAW, null),
                rawEnabled = { true },
                firmwareDump = "Device: SDMDevice colorType=1 penState=2\nPenButton: TIP=0",
            )
        }
        compose.onNodeWithText("Device: SDMDevice colorType=1 penState=2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("PenButton: TIP=0", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_ink_experiment_toggles_show_their_state_and_flip_one_at_a_time() {
        var toggles by mutableStateOf(InkExperiments(vendorResumeOrder = true, regionPenConfig = false))
        compose.setContent {
            DiagnosticsPanel(
                InputStatus(InputPath.ONYX_RAW, null),
                rawEnabled = { true },
                inkExperiments = toggles,
                onInkExperiments = { toggles = it },
            )
        }
        compose.onNodeWithText("Vendor resume order: on").assertIsDisplayed()
        compose.onNodeWithText("Region pen config: off").performClick()
        assertThat(toggles).isEqualTo(InkExperiments(vendorResumeOrder = true, regionPenConfig = true))
        compose.onNodeWithText("Vendor resume order: on").performClick()
        assertThat(toggles).isEqualTo(InkExperiments(vendorResumeOrder = false, regionPenConfig = true))
        compose.onNodeWithText("Vendor resume order: off").assertIsDisplayed()
    }
}
