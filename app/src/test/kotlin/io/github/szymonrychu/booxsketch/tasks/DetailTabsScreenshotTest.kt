package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
class DetailTabsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(
        tab: SubTab,
        name: String,
    ) {
        val task =
            taskJson(
                "T-12",
                "todo" to TODO_SHOT,
                "comments" to COMMENTS_SHOT,
                "events" to eventsJson(4),
            )
        val relay = FakeRelay { json(task) }
        val vm = TaskDetailViewModel(relay.client, "T-12", emptyFlow(), CoroutineScope(Job() + Dispatchers.Unconfined))
        compose.setContent { EinkTheme { TaskDetailScreen(vm, {}, {}) } }
        compose.waitUntil(5_000) { vm.state.value.task != null }
        compose.onNodeWithTag(subTabTag(tab)).performClick()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
        relay.shutdown()
    }

    @Test
    fun todo() = shoot(SubTab.TODO, "tasks_todo")

    @Test
    fun comments() = shoot(SubTab.COMMENTS, "tasks_comments")

    @Test
    fun history() = shoot(SubTab.HISTORY, "tasks_history")
}

private const val TODO_SHOT =
    """[{"id":7,"text":"Write client","status":"done"},""" +
        """{"id":8,"text":"Write screens","status":"inprogress"},{"id":9,"text":"Run on device","status":"todo"}]"""
private const val COMMENTS_SHOT =
    """[{"id":3,"author_kind":"user","body":"Looks good","created_at":"2026-11-02T09:00:00Z"},""" +
        """{"id":4,"author_kind":"agent","author_slug":"alpha-fox","body":"Done","created_at":"2026-11-02T09:30:00Z"}]"""
