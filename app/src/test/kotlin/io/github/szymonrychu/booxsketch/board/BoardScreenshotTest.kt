package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h900dp-xhdpi")
class BoardScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private fun stroke(
        id: String,
        tool: Tool,
        y: Float,
    ) = Stroke(id, tool, 6f, (0..20).map { Point(100f + it * 50f, y + (it % 5) * 30f, 0.5f + it % 3 * 0.2f, it * 10L) })

    @Test
    fun board_with_strokes_on_page_2_of_3() {
        val vm =
            BoardViewModel(
                NotebookStore(File(tmp.root, "n.json")),
                CoroutineScope(Dispatchers.Unconfined),
                Dispatchers.Unconfined,
                Canvas(1404, 1872, 300f),
            )
        vm.onStroke(stroke("p1", Tool.BALLPOINT, 300f))
        vm.addPage()
        vm.onStroke(stroke("a", Tool.FOUNTAIN, 300f))
        vm.onStroke(stroke("b", Tool.MARKER, 700f))
        vm.onStroke(stroke("c", Tool.CHARCOAL, 1100f))
        vm.addPage()
        vm.selectPage(1)
        compose.setContent {
            MaterialTheme {
                Column {
                    val nb by vm.notebook.collectAsState()
                    BoardPreviewCanvas(nb.canvas, nb.pages[nb.current], Modifier.fillMaxWidth().height(400.dp))
                    BoardToolbar(vm)
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/board_page2of3.png")
    }
}
