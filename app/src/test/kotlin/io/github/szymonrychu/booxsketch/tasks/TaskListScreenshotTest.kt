package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.api.TaskDto
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class TaskListScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val json = Json { ignoreUnknownKeys = true }

    private fun task(
        key: String,
        vararg fields: Pair<String, String>,
    ): TaskDto = json.decodeFromString(taskJson(key, *fields))

    private fun show(state: TaskListState) {
        compose.setContent {
            EinkTheme { TaskListContent(state, {}, {}, {}, {}, {}, {}, {}, {}) }
        }
    }

    @Test
    fun task_list() {
        val items =
            listOf(
                task(
                    "T-12",
                    "title" to "\"Wire the tasks tab\"",
                    "labels" to "[\"boox\",\"ui\"]",
                    "assignee_session" to "\"sess-a\"",
                    "open_decisions" to "1",
                ),
                task("T-11", "title" to "\"Write the docs\"", "status" to "\"in_progress\""),
                task("T-9", "title" to "\"Fix the pairing flow\"", "status" to "\"done\"", "labels" to "[\"bug\"]"),
            )
        show(TaskListState(items = items, labels = listOf("boox"), pageIndex = 1, hasNext = true, loading = false))
        compose.onRoot().captureRoboImage("src/test/screenshots/tasks_list.png")
    }

    @Test
    fun task_list_empty() {
        show(TaskListState(loading = false))
        compose.onRoot().captureRoboImage("src/test/screenshots/tasks_list_empty.png")
    }
}
