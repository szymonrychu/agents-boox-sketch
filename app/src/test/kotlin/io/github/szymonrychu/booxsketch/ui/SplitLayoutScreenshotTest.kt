package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Layout goldens at the BOOX Note Air 6C size (see MEMORY.md for the dp figures). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class SplitLayoutShots {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        PanelTabs.clearForTest()
        PanelTabs.register(PanelTab("spec", "Spec", 10) { Text("Spec body") })
        PanelTabs.register(PanelTab("thread", "Thread", 20) { Text("Thread body") })
    }

    @After
    fun tearDown() = PanelTabs.clearForTest()

    protected fun shoot(
        state: SplitState,
        name: String,
        insets: WindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        compose.setContent {
            EinkTheme {
                SplitLayout(
                    board = { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Board") } },
                    panel = { PanelShell() },
                    state = state,
                    insets = insets,
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}

@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class SplitLayoutLandscapeShots : SplitLayoutShots() {
    @Test
    fun landscape() = shoot(SplitState(0.6f, false), "split_landscape")

    @Test
    fun landscape_insets() =
        shoot(SplitState(0.6f, false), "split_landscape_insets", WindowInsets(left = 40, top = 24, right = 0, bottom = 48))

    @Test
    fun landscape_collapsed() = shoot(SplitState(0.6f, true), "split_landscape_collapsed")
}

@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class SplitLayoutPortraitShots : SplitLayoutShots() {
    @Test
    fun portrait() = shoot(SplitState(0.6f, false), "split_portrait")

    @Test
    fun portrait_insets() =
        shoot(SplitState(0.6f, false), "split_portrait_insets", WindowInsets(left = 0, top = 24, right = 0, bottom = 48))
}
