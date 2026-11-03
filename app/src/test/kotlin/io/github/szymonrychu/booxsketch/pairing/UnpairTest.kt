package io.github.szymonrychu.booxsketch.pairing

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.App
import io.github.szymonrychu.booxsketch.AppContainer
import io.github.szymonrychu.booxsketch.PairedApp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PairedApp::class, sdk = [35])
class UnpairTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unpair_clears_token_tables_and_notebook() =
        runBlocking {
            val c: AppContainer = ApplicationProvider.getApplicationContext<App>().container
            c.notebookStore.save(c.boardViewModel.notebook.value)
            assertThat(c.notebookStore.load()).isNotNull()
            c.unpair()
            assertThat(c.tokenStore.token()).isNull()
            assertThat(c.tokenStore.origin()).isNull()
            assertThat(c.notebookStore.load()).isNull()
        }

    @Test
    fun settings_tab_shows_origin_and_unpair_clears_token() {
        val c: AppContainer = ApplicationProvider.getApplicationContext<App>().container
        compose.setContent { SettingsTab(Modifier) }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(TAG_PAIRED_ORIGIN).assertTextEquals("https://connector.example") }.isSuccess
        }
        compose.onNodeWithTag(TAG_UNPAIR).performClick()
        compose.waitUntil(5_000) { c.tokenStore.token() == null }
    }
}
