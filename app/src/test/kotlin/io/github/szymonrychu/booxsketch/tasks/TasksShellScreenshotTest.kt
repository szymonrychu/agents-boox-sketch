package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.tasks.FakeRelay.Companion.json
import io.github.szymonrychu.booxsketch.ui.AppShell
import io.github.szymonrychu.booxsketch.ui.BOARD_TAB_ID
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import io.github.szymonrychu.booxsketch.ui.ShellState
import io.github.szymonrychu.booxsketch.ui.TabRegistry
import io.github.szymonrychu.booxsketch.ui.TabSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class TasksShellScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tasks_tab_in_the_shell() {
        val items = listOf(taskJson("T-12", "title" to "\"Wire the tasks tab\"", "labels" to "[\"boox\"]", "open_decisions" to "1"))
        val relay = FakeRelay { json(pageJson(items)) }
        val controller = TasksController({ relay.client }, emptyFlow(), CoroutineScope(Job() + Dispatchers.Unconfined), {})
        val tabs = TabRegistry()
        tabs.register(TabSpec(BOARD_TAB_ID, "Drawing", 10) { })
        tabs.register(TabSpec(TASKS_TAB_ID, "Tasks", 20) { TasksHost(controller, it) })
        compose.setContent { EinkTheme { AppShell(tabs, board = { }, state = ShellState(selected = TASKS_TAB_ID)) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(taskRowTag("T-12"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("src/test/screenshots/tasks_tab_in_shell.png")
        relay.shutdown()
    }
}
