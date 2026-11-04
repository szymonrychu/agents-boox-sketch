package io.github.szymonrychu.booxsketch.ui

import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TAG_SHELL = "app_shell_root"
private const val TAG_FAKE_BOARD = "fake_board"

@RunWith(RobolectricTestRunner::class)
abstract class AppShellTestBase {
    @get:Rule val compose = createComposeRule()

    private val registry = TabRegistry()
    private val state = ShellState()
    private var chrome: BoardChrome? = null
    private var boardDisposed = 0
    private var boardEntered = 0
    private var refreshed = 0

    /** Stands in for `AppContainer.selectTab`, which the shell host wires to `ShellState.select`. */
    private val selectHook: (String) -> Unit = { state.select(it) }

    @Before
    fun registerTabs() {
        registry.register(TabSpec(BOARD_TAB_ID, "Drawings", 10) { })
        registry.register(TabSpec("spec", "Spec", 20) { Box(it.testTag("spec_content")) { Text("spec body") } })
        registry.register(
            TabSpec("conversations", "Conversations", 40) {
                Box(it.testTag("conversations_content")) {
                    Text("pick", Modifier.testTag("pick_thread").clickable { selectHook("thread") })
                }
            },
        )
        registry.register(TabSpec("thread", "Conversation", 30) { Box(it.testTag("thread_content")) })
    }

    private fun show() {
        compose.setContent {
            AppShell(
                tabs = registry,
                board = { c ->
                    chrome = c
                    DisposableEffect(Unit) {
                        boardEntered++
                        onDispose { boardDisposed++ }
                    }
                    Box(Modifier.fillMaxSize().testTag(TAG_FAKE_BOARD))
                },
                modifier = Modifier.testTag(TAG_SHELL),
                state = state,
                refresh = { _: View -> refreshed++ },
                insets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    @Test
    fun board_tab_shows_board_and_tab_bar() {
        show()
        compose.onNodeWithTag(TAG_FAKE_BOARD).assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAB_BAR).assertIsDisplayed()
    }

    @Test
    fun board_fills_whole_box_under_tab_bar() {
        show()
        val root = compose.onNodeWithTag(TAG_SHELL).fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag(TAG_FAKE_BOARD).fetchSemanticsNode().boundsInRoot
        assertThat(board).isEqualTo(root)
    }

    @Test
    fun hidden_chrome_hides_tab_bar_and_clears_overlays() {
        show()
        compose.runOnIdle { chrome!!.hide() }
        compose.onNodeWithTag(TAG_TAB_BAR).assertDoesNotExist()
        compose.runOnIdle {
            assertThat(chrome!!.visible).isFalse()
            assertThat(chrome!!.overlays).isEmpty()
        }
    }

    @Test
    fun overlays_hold_tab_bar_rect_while_visible() {
        show()
        val bar = compose.onNodeWithTag(TAG_TAB_BAR).fetchSemanticsNode().boundsInWindow
        compose.runOnIdle {
            val rect = chrome!!.overlays.single()
            assertThat(rect.left).isEqualTo(bar.left.toInt())
            assertThat(rect.top).isEqualTo(bar.top.toInt())
            assertThat(rect.right).isEqualTo(bar.right.toInt())
            assertThat(rect.bottom).isEqualTo(bar.bottom.toInt())
        }
    }

    @Test
    fun selecting_registered_tab_shows_its_content_and_disposes_board() {
        show()
        compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + "spec").performClick()
        compose.onNodeWithTag("spec_content").assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAB_BAR).assertIsDisplayed()
        compose.onNodeWithTag(TAG_FAKE_BOARD).assertDoesNotExist()
        compose.runOnIdle { assertThat(boardDisposed).isEqualTo(1) }
    }

    @Test
    fun returning_to_board_recomposes_board_with_chrome_visible() {
        show()
        compose.runOnIdle { chrome!!.hide() }
        compose.runOnIdle { state.select("spec") }
        compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + BOARD_TAB_ID).performClick()
        compose.onNodeWithTag(TAG_FAKE_BOARD).assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAB_BAR).assertIsDisplayed()
        compose.runOnIdle { assertThat(boardEntered).isEqualTo(2) }
    }

    /** Commits a stroke and applies the snapshot write, which the paused test clock would defer. */
    private fun strokeCommitted() {
        compose.runOnIdle {
            chrome!!.onStrokeCommitted()
            Snapshot.sendApplyNotifications()
        }
    }

    @Test
    fun auto_hide_after_quiet_gap() {
        show()
        compose.mainClock.autoAdvance = false
        strokeCommitted()
        compose.mainClock.advanceTimeBy(599)
        assertThat(state.chromeVisible).isTrue()
        compose.mainClock.advanceTimeBy(2)
        assertThat(state.chromeVisible).isFalse()
    }

    @Test
    fun refused_auto_hide_is_retried_after_another_quiet_gap() {
        show()
        var allow = false
        var calls = 0
        compose.runOnIdle {
            chrome!!.autoHideHandler = {
                calls++
                if (allow) state.hide()
                allow
            }
        }
        compose.mainClock.autoAdvance = false
        strokeCommitted()
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.advanceTimeBy(601)
        assertThat(calls).isEqualTo(1)
        assertThat(state.chromeVisible).isTrue()
        allow = true
        compose.mainClock.advanceTimeBy(600)
        assertThat(calls).isEqualTo(2)
        assertThat(state.chromeVisible).isFalse()
    }

    @Test
    fun second_stroke_restarts_quiet_gap() {
        show()
        compose.mainClock.autoAdvance = false
        strokeCommitted()
        compose.mainClock.advanceTimeBy(400)
        strokeCommitted()
        compose.mainClock.advanceTimeBy(400)
        assertThat(state.chromeVisible).isTrue()
        compose.mainClock.advanceTimeBy(300)
        assertThat(state.chromeVisible).isFalse()
    }

    @Test
    fun tab_change_calls_refresh_except_into_board() {
        show()
        compose.runOnIdle { assertThat(refreshed).isEqualTo(0) }
        compose.runOnIdle { state.select("spec") }
        compose.runOnIdle { assertThat(refreshed).isEqualTo(1) }
        compose.runOnIdle { state.select(BOARD_TAB_ID) }
        compose.runOnIdle { assertThat(refreshed).isEqualTo(1) }
    }

    @Test
    fun unknown_selected_tab_falls_back_to_board() {
        show()
        compose.runOnIdle { state.select("missing") }
        compose.onNodeWithTag(TAG_FAKE_BOARD).assertIsDisplayed()
    }

    @Test
    fun two_tabs_show_tab_bar() {
        show()
        compose.onNodeWithTag(TAG_TAB_BAR).assertIsDisplayed()
    }

    @Test
    fun single_tab_shows_no_tab_bar() {
        val single = TabRegistry()
        single.register(TabSpec(BOARD_TAB_ID, "Drawing", 10) { })
        compose.setContent {
            AppShell(
                tabs = single,
                board = { c ->
                    chrome = c
                    Box(Modifier.fillMaxSize().testTag(TAG_FAKE_BOARD))
                },
                state = state,
                insets = WindowInsets(0, 0, 0, 0),
            )
        }
        compose.onNodeWithTag(TAG_FAKE_BOARD).assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAB_BAR).assertDoesNotExist()
        compose.runOnIdle { assertThat(chrome!!.overlays).isEmpty() }
    }

    @Test
    fun auto_hide_also_hides_panel() {
        show()
        // Only the panel is visible: it alone arms the auto-hide.
        compose.runOnIdle {
            chrome!!.hide()
            chrome!!.showPanel()
        }
        compose.mainClock.autoAdvance = false
        strokeCommitted()
        compose.mainClock.advanceTimeBy(ShellState.AUTO_HIDE_AFTER_STROKE_MS + 1)
        assertThat(chrome!!.panelVisible).isFalse()
        assertThat(state.chromeVisible).isFalse()
    }

    @Test
    fun select_hook_from_content_switches_tab() {
        show()
        compose.runOnIdle { state.select("conversations") }
        compose.onNodeWithTag("pick_thread").performClick()
        compose.onNodeWithTag("thread_content").assertIsDisplayed()
    }
}

@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class AppShellLandscapeTest : AppShellTestBase()

@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class AppShellPortraitTest : AppShellTestBase()
