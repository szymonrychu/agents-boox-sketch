package io.github.szymonrychu.booxsketch

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.szymonrychu.booxsketch.board.TAG_PAGE_LABEL
import io.github.szymonrychu.booxsketch.board.TAG_SEND
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MainActivityBoardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchingShowsTheBoardToolbar() {
        compose.onNodeWithTag(TAG_SEND).assertExists()
        compose.onNodeWithTag(TAG_PAGE_LABEL).assertExists()
    }
}
