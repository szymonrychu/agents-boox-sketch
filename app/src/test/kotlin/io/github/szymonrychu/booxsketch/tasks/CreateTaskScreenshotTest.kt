package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class CreateTaskScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun create_form() {
        val relay = FakeRelay()
        val vm = CreateTaskViewModel(relay.client, CoroutineScope(Job() + Dispatchers.Unconfined), {})
        vm.setTitle("Add dark mode to the board")
        vm.setBody("Needs a design pass first.")
        vm.addLabel("boox")
        compose.setContent { EinkTheme { CreateTaskScreen(vm, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/tasks_create.png")
        relay.shutdown()
    }
}
