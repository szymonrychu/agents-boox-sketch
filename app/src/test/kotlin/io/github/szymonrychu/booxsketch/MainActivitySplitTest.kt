package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.szymonrychu.booxsketch.ui.SplitState
import io.github.szymonrychu.booxsketch.ui.TAG_PANEL
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PairedApp::class, sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class MainActivitySplitTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun saved_collapse_is_applied_after_an_async_read() {
        val container = ApplicationProvider.getApplicationContext<App>().container
        runBlocking { container.splitStateStore.save(SplitState(0.5f, true)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasTestTag(TAG_PANEL)).fetchSemanticsNodes(false).isEmpty()
            }
        }
    }
}
