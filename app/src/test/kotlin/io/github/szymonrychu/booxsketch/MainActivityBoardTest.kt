package io.github.szymonrychu.booxsketch

import android.graphics.Insets
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.szymonrychu.booxsketch.board.stripTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = PairedApp::class, sdk = [35])
class MainActivityBoardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchingShowsTheBoardToolbar() {
        compose.onNodeWithTag(stripTag("send")).assertExists()
        compose.onNodeWithTag(stripTag("pages")).assertExists()
    }

    @Test
    fun toolbarIsNotDrawnUnderTheNavigationBar() {
        val bar = 96
        compose.runOnUiThread {
            val host = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val insets =
                WindowInsets
                    .Builder()
                    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bar))
                    .setVisible(WindowInsets.Type.navigationBars(), true)
                    .build()
            host.getChildAt(0).dispatchApplyWindowInsets(insets)
        }
        compose.waitForIdle()
        val height =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.height
        val bottom =
            compose
                .onNodeWithTag(stripTag("send"))
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertTrue("toolbar bottom $bottom is under the $bar px nav bar (height $height)", bottom <= height - bar)
    }
}
