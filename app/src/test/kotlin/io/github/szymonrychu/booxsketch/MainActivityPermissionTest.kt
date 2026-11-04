package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A permission dialog at launch leaves the activity paused (the emulator LaunchTest needs RESUMED). */
@RunWith(RobolectricTestRunner::class)
@Config(application = PairedApp::class, sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class MainActivityPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launch_does_not_ask_for_the_notification_permission() {
        compose.waitForIdle()
        assertThat(shadowOf(ApplicationProvider.getApplicationContext<App>()).nextStartedActivity).isNull()
    }
}
