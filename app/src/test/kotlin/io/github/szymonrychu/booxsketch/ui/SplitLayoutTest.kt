package io.github.szymonrychu.booxsketch.ui

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.board.BoardView
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class SplitLayoutTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun reset() = PanelTabs.clearForTest()

    @After
    fun cleanup() = PanelTabs.clearForTest()

    private fun store(scope: CoroutineScope) = SplitStateStore.create(scope, File(tmp.root, "split.preferences_pb"))

    @Test
    fun ratio_persists_across_recreate() =
        runBlocking {
            val job1 = Job()
            store(CoroutineScope(job1 + Dispatchers.IO)).save(SplitState(0.45f, true))
            job1.cancelAndJoin()
            val job2 = Job()
            val loaded = store(CoroutineScope(job2 + Dispatchers.IO)).load()
            job2.cancelAndJoin()
            assertThat(loaded).isEqualTo(SplitState(0.45f, true))
        }

    @Test
    fun stored_ratio_is_clamped_to_the_drag_range() =
        runBlocking {
            val job = Job()
            val s = store(CoroutineScope(job + Dispatchers.IO))
            s.save(SplitState(0.95f, false))
            assertThat(s.load().ratio).isEqualTo(SplitState.MAX_RATIO)
            s.save(SplitState(0.05f, false))
            assertThat(s.load().ratio).isEqualTo(SplitState.MIN_RATIO)
            job.cancel()
        }

    @Test
    fun collapse_hides_panel() {
        var state by mutableStateOf(SplitState(0.6f, false))
        compose.setContent {
            SplitLayout(
                board = { Text("board") },
                panel = { Text("panel") },
                state = state,
                onStateChange = { state = it },
            )
        }
        compose.onNodeWithText("panel").assertIsDisplayed()
        compose.onNodeWithTag(TAG_COLLAPSE).performClick()
        compose.onNodeWithTag(TAG_PANEL).assertDoesNotExist()
        compose.onNodeWithText("board").assertIsDisplayed()
        assertThat(state.collapsed).isTrue()
        compose.onNodeWithTag(TAG_COLLAPSE).performClick()
        compose.onNodeWithText("panel").assertIsDisplayed()
    }

    @Test
    fun divider_drag_stays_within_range() {
        var state by mutableStateOf(SplitState(0.6f, false))
        compose.setContent {
            SplitLayout(
                board = { Text("board", Modifier.fillMaxSize()) },
                panel = { Text("panel") },
                state = state,
                onStateChange = { state = it },
            )
        }
        compose.onNodeWithTag(TAG_DIVIDER).performTouchInput { swipeRight(startX = 2f, endX = 5000f) }
        assertThat(state.ratio).isEqualTo(SplitState.MAX_RATIO)
    }

    @Test
    fun registered_tabs_shown_in_order() {
        PanelTabs.register(PanelTab("thread", "Thread", 20) { Text("thread body") })
        PanelTabs.register(PanelTab("spec", "Spec", 10) { Text("spec body") })
        assertThat(PanelTabs.tabs.map { it.id }).containsExactly("spec", "thread").inOrder()
        compose.setContent { PanelShell() }
        compose.onNodeWithText("spec body").assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAB_PREFIX + "thread").performClick()
        compose.onNodeWithText("thread body").assertIsDisplayed()
        compose.onNodeWithText("Spec").assertIsDisplayed()
    }

    @Test
    fun no_tabs_shows_empty_state() {
        compose.setContent { PanelShell() }
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun conversation_button_calls_the_hook() {
        var opened = 0
        PanelTabs.onOpenConversations = { opened++ }
        compose.setContent { PanelShell() }
        compose.onNodeWithTag(TAG_CONVERSATION).performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun content_stays_inside_system_bar_and_cutout_insets() {
        compose.setContent {
            SplitLayout(
                board = { Text("Board", Modifier.testTag("board")) },
                panel = { PanelShell() },
                state = SplitState(0.6f, false),
                insets = WindowInsets(left = 40, top = 24, right = 10, bottom = 48),
            )
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag(TAG_PANEL).fetchSemanticsNode().boundsInRoot
        val divider = compose.onNodeWithTag(TAG_DIVIDER).fetchSemanticsNode().boundsInRoot
        listOf(board, panel, divider).forEach {
            assertThat(it.left).isAtLeast(40f)
            assertThat(it.top).isAtLeast(24f)
            assertThat(it.right).isAtMost(root.right - 10f)
            assertThat(it.bottom).isAtMost(root.bottom - 48f)
        }
    }

    @Test
    fun board_bounds_passed_to_stroke_input_do_not_overlap_system_bar_insets() {
        val input = BoundsRecordingInput()
        val bar = WindowInsets(left = 40, top = 24, right = 10, bottom = 48)
        compose.setContent {
            SplitLayout(
                board = {
                    BoardView(
                        notebook = Notebook(Canvas(100, 200, 300f), listOf(Page(emptyList(), false)), 0),
                        input = input,
                        onStroke = {},
                        onErase = { _, _, _ -> },
                        modifier = Modifier.fillMaxSize().testTag("board"),
                    )
                },
                panel = { PanelShell() },
                state = SplitState(0.6f, false),
                insets = bar,
            )
        }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").fetchSemanticsNode().boundsInRoot
        val limit = checkNotNull(input.limit) { "StrokeInput.start was never called" }
        // The limit is the board's own bounds in view pixels, with no extra bar arithmetic.
        assertThat(limit.width()).isEqualTo(board.width.toInt())
        assertThat(limit.height()).isEqualTo(board.height.toInt())
        // In window coordinates the board, and so the limit, stays clear of every bar.
        assertThat(board.left).isAtLeast(40f)
        assertThat(board.top).isAtLeast(24f)
        assertThat(board.right).isAtMost(root.right - 10f)
        assertThat(board.bottom).isAtMost(root.bottom - 48f)
    }
}

private class BoundsRecordingInput : StrokeInput {
    var limit: Rect? = null
    override val events: Flow<StrokeEvent> = emptyFlow()

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        this.limit = Rect(limit)
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = Unit
}
