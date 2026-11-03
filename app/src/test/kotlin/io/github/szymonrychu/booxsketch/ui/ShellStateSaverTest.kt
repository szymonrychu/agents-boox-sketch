package io.github.szymonrychu.booxsketch.ui

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class ShellStateSaverTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun saver_round_trip_keeps_tab_and_visibility() {
        val restoration = StateRestorationTester(compose)
        var state: ShellState? = null
        restoration.setContent { state = rememberShellState() }
        compose.runOnIdle {
            state!!.select("spec")
            state!!.hide()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertThat(state!!.selected).isEqualTo("spec")
            assertThat(state!!.chromeVisible).isFalse()
        }
    }
}
