package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.board.TAG_POPUP
import io.github.szymonrychu.booxsketch.board.TAG_SEND
import io.github.szymonrychu.booxsketch.board.TAG_SEND_ALL
import io.github.szymonrychu.booxsketch.board.stripTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The send path through the icon strip: strip button, send popup, then the permission request of the first send. */
@RunWith(RobolectricTestRunner::class)
@Config(application = PairedApp::class, sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class MainActivitySendTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun nextStarted() = shadowOf(ApplicationProvider.getApplicationContext<App>()).nextStartedActivity

    private fun openSendPopup() {
        compose.onNodeWithTag(stripTag("send")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_POPUP).assertExists()
    }

    @Test
    fun send_asks_notification_permission_once_per_send_not_at_launch() {
        compose.waitForIdle()
        assertThat(nextStarted()).isNull()
        openSendPopup()
        // Opening the popup asks for nothing; the send does.
        assertThat(nextStarted()).isNull()
        compose.onNodeWithTag(TAG_SEND).performClick()
        compose.waitForIdle()
        assertThat(nextStarted()).isNotNull()
        assertThat(nextStarted()).isNull()
        compose.onNodeWithTag(TAG_POPUP).assertDoesNotExist()
    }

    @Test
    fun send_all_button_asks_for_the_permission_too() {
        openSendPopup()
        compose.onNodeWithTag(TAG_SEND_ALL).performClick()
        compose.waitForIdle()
        assertThat(nextStarted()).isNotNull()
    }
}
