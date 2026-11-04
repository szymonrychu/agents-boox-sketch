package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.board.TAG_DOCK
import io.github.szymonrychu.booxsketch.board.TAG_INDICATOR
import io.github.szymonrychu.booxsketch.board.TAG_PANEL_HOST
import io.github.szymonrychu.booxsketch.board.TAG_PANEL_INDICATOR
import io.github.szymonrychu.booxsketch.conversations.CONVERSATIONS_TAB_ID
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.tasks.TASKS_TAB_ID
import io.github.szymonrychu.booxsketch.ui.BOARD_TAB_ID
import io.github.szymonrychu.booxsketch.ui.ShellState
import io.github.szymonrychu.booxsketch.ui.TAG_TAB_BAR
import io.github.szymonrychu.booxsketch.ui.TAG_TAB_PREFIX
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MainActivity hosts the tabbed AppShell with the board as the Drawing tab and the side panel over it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FreezingInputApp::class, sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class MainActivityShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun launch_shows_dock_and_no_panel() {
        waitFor(TAG_DOCK)
        compose.onNodeWithTag(TAG_DOCK).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PANEL_HOST).assertDoesNotExist()
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).assertIsDisplayed()
    }

    @Test
    fun launch_registers_the_drawing_and_tasks_tabs() {
        waitFor(TAG_DOCK)
        val container = (compose.activity.application as App).container
        assertThat(container.tabs.tabs.map { it.id }).containsExactly(BOARD_TAB_ID, TASKS_TAB_ID).inOrder()
        assertThat(container.tabs.tabs.map { it.title }).containsExactly("Drawing", "Tasks").inOrder()
    }

    @Test
    fun chrome_is_wired_so_auto_hide_works() {
        waitFor(TAG_DOCK)
        compose.mainClock.autoAdvance = false
        FreezingInputApp.strokes.trySend(StrokeEvent.Committed(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))))
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExist()
        compose.onNodeWithTag(TAG_INDICATOR).assertIsDisplayed()
    }

    @Test
    fun panel_indicator_opens_panel_with_conversations_tab() {
        waitFor(TAG_PANEL_INDICATOR)
        compose.onNodeWithTag(TAG_PANEL_INDICATOR).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_PANEL_HOST).assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAB_PREFIX + CONVERSATIONS_TAB_ID).assertIsDisplayed()
        compose.onNodeWithText("No conversations yet. Send a sketch to start one.").assertIsDisplayed()
    }
}
