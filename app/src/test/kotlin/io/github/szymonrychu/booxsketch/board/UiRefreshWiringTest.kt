package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.UiRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A screen-freezing input that logs its holds and releases. */
private class FreezingInput : StrokeInput {
    val log = mutableListOf<String>()
    override val events: Flow<StrokeEvent> = emptyFlow()
    override val freezesUi = true

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() {
        log += "stop"
    }

    override fun holdUi() {
        log += "hold"
    }

    override fun releaseUi() {
        log += "release"
    }
}

private class RecordingUi : UiRefresh {
    val calls = mutableListOf<Boolean>()

    override fun uiChanged(settled: Boolean) {
        calls += settled
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiRefreshWiringTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private fun vm() =
        BoardViewModel(NotebookStore(File(tmp.root, "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun toolbar_buttons_report_settled_changes() {
        val ui = RecordingUi()
        compose.setContent { BoardToolbar(vm(), ui = ui) }
        compose.onNodeWithText(Tool.entries.first().wire).performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Add page").performClick()
        compose.onNodeWithText("Previous").performClick()
        assertThat(ui.calls).containsExactly(true, true, true, true)
    }

    @Test
    fun width_drag_reports_unsettled_changes_then_one_settled_on_release() {
        val ui = RecordingUi()
        compose.setContent { BoardToolbar(vm(), ui = ui) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) {
            it(10f)
        }
        assertThat(ui.calls).containsExactly(false, true).inOrder()
    }

    @Test
    fun board_holds_raw_drawing_until_the_first_frame_and_brackets_toolbar_changes_only() {
        val input = FreezingInput()
        val vm = vm()
        compose.mainClock.autoAdvance = false
        compose.setContent { BoardScreen(vm, input) }
        assertThat(input.log.firstOrNull()).isEqualTo("hold")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(input.log).containsExactly("hold", "release").inOrder()

        compose.onNodeWithText(Tool.entries.first().wire).performClick()
        compose.waitForIdle()
        assertThat(input.log).containsExactly("hold", "release", "hold", "release").inOrder()

        // The pen path (a committed stroke) never toggles raw drawing.
        compose.runOnIdle { vm.onStroke(Stroke("s", Tool.BALLPOINT, 4f, listOf(Point(1f, 1f, 0.5f, 0L)))) }
        compose.waitForIdle()
        assertThat(input.log).hasSize(4)
    }

    @Test
    fun leaving_the_board_stops_the_input_before_the_refresher_releases_its_holds() {
        val input = FreezingInput()
        val vm = vm()
        var shown by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { if (shown) BoardScreen(vm, input) }
        assertThat(input.log).containsExactly("hold")
        compose.runOnIdle { shown = false }
        compose.mainClock.advanceTimeByFrame()
        assertThat(input.log.indexOf("stop")).isAtLeast(0)
        val releasesAfterDispose = input.log.filterIndexed { i, e -> e == "release" && i < input.log.indexOf("stop") }
        assertThat(releasesAfterDispose).isEmpty()
    }

    @Test
    fun touch_input_gets_the_no_op_refresh() {
        val view = View(ApplicationProvider.getApplicationContext())
        assertThat(UiRefresher.forView(view, TouchStrokeInput())).isSameInstanceAs(UiRefresh.None)
    }
}
