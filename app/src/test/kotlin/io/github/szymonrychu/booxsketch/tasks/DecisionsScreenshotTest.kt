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
import okhttp3.mockwebserver.MockResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class DecisionsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(
        task: String,
        name: String,
        post: MockResponse = json("{}"),
        afterLoad: (TaskDetailViewModel) -> Unit = {},
    ) {
        val relay = FakeRelay { if (it.method == "POST") post else json(task) }
        val vm = TaskDetailViewModel(relay.client, "T-12", emptyFlow(), CoroutineScope(Job() + Dispatchers.Unconfined))
        compose.setContent { EinkTheme { TaskDetailScreen(vm, {}, {}) } }
        compose.waitUntil(5_000) { vm.state.value.task != null }
        compose.onNodeWithTag(subTabTag(SubTab.DECISIONS)).performClick()
        afterLoad(vm)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
        relay.shutdown()
    }

    @Test
    fun open() {
        shoot(taskWith(openDecision()), "tasks_decisions_open") { compose.onNodeWithTag(decisionOptionTag(21, 0)).performClick() }
    }

    @Test
    fun answered() {
        shoot(taskWith(answeredDecision()), "tasks_decisions_answered")
    }

    @Test
    fun withdrawn() {
        shoot(taskWith(WITHDRAWN_DECISION), "tasks_decisions_withdrawn")
    }

    @Test
    fun already_answered_banner() {
        shoot(
            taskWith(openDecision()),
            "tasks_decisions_already_answered",
            post = FakeRelay.taskboardError(409, "already_answered", "decision is already answered"),
        ) { vm ->
            vm.answer(21, 0, null, null)
            compose.waitUntil(5_000) {
                vm.state.value.decisionNotice
                    .isNotEmpty()
            }
        }
    }
}
