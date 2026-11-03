package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.szymonrychu.booxsketch.board.TAG_SEND
import io.github.szymonrychu.booxsketch.pairing.FakeKeyProvider
import io.github.szymonrychu.booxsketch.pairing.TAG_PAIR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class UnpairedApp : App() {
    override fun newContainer(): AppContainer = AppContainer(this, keyProvider = FakeKeyProvider())
}

@RunWith(AndroidJUnit4::class)
@Config(application = UnpairedApp::class, sdk = [35])
class MainActivityPairingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun unpaired_start_shows_pairing_instead_of_board() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(TAG_PAIR)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TAG_PAIR).assertExists()
        compose.onNodeWithTag(TAG_SEND).assertDoesNotExist()
    }
}
