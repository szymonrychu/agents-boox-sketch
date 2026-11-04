package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.tasks.FakeRelay.Companion.json
import io.github.szymonrychu.booxsketch.ui.EinkTheme
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
class TaskDetailScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(
        key: String,
        relay: FakeRelay,
        name: String,
    ) {
        val vm = TaskDetailViewModel(relay.client, key, emptyFlow(), CoroutineScope(Job() + Dispatchers.Unconfined))
        compose.setContent { EinkTheme { TaskDetailScreen(vm, {}, {}) } }
        compose.waitUntil(5_000) { vm.state.value.task != null || vm.state.value.notFound }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
        relay.shutdown()
    }

    @Test
    fun overview() {
        val task =
            taskJson(
                "T-12",
                "title" to "\"Wire the tasks tab\"",
                "status" to "\"in_progress\"",
                "body" to "\"Register the tab and test it on the device.\"",
                "labels" to "[\"boox\",\"ui\"]",
                "open_decisions" to "1",
                "waiting_sessions" to """[{"session_id":"sess-a","slug":"alpha-fox"}]""",
            )
        shoot("T-12", FakeRelay { json(task) }, "tasks_detail_overview")
    }

    @Test
    fun not_found() {
        shoot("T-9", FakeRelay { FakeRelay.taskboardError(404, "not_found", "no task T-9") }, "tasks_detail_not_found")
    }
}
