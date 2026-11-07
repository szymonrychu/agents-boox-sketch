package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
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
                Row {
                    val nb by vm.notebook.collectAsState()
                    ToolStrip(vm, ToolboxState(), {}, UiRefresh.None)
                    BoardPreviewCanvas(nb.canvas, nb.pages[nb.current], Modifier.weight(1f).height(400.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/board_page2of3.png")
    }

    private fun shape(
        tool: Tool,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: PenColor,
    ) = Stroke("$tool$x0", tool, 6f, listOf(Point(x0, y0, 1f, 0L), Point(x1, y1, 1f, 10L)), color)

    @Test
    fun colours_and_shapes_with_red_rectangle_selected() {
        val vm =
            BoardViewModel(
                NotebookStore(File(tmp.root, "n.json")),
                CoroutineScope(Dispatchers.Unconfined),
                Dispatchers.Unconfined,
                Canvas(1404, 1872, 300f),
            )
        vm.onStroke(shape(Tool.RECTANGLE, 150f, 200f, 600f, 500f, PenColor.RED))
        vm.onStroke(shape(Tool.RECTANGLE, 800f, 900f, 1250f, 1200f, PenColor.BLUE))
        // Ends land near anchors (right-middle of the red box, top-middle of the blue one) and snap.
        vm.onStroke(shape(Tool.ARROW, 610f, 360f, 1020f, 880f, PenColor.BLACK))
        vm.onStroke(stroke("g", Tool.BALLPOINT, 1400f).copy(color = PenColor.GREEN))
        vm.onStroke(stroke("o", Tool.MARKER, 1600f).copy(color = PenColor.ORANGE))
        vm.setColor(PenColor.RED)
        vm.setTool(Tool.RECTANGLE)
        compose.setContent {
            MaterialTheme {
                Row {
                    val nb by vm.notebook.collectAsState()
                    ToolStrip(vm, ToolboxState(), {}, UiRefresh.None)
                    BoardPreviewCanvas(nb.canvas, nb.pages[nb.current], Modifier.weight(1f).height(400.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/board_colours_shapes.png")
    }

    /** A notebook page with three text runs (one heading, one with a red span), drawing ink and a pending tick. */
    private fun notebookPage(): Page {
        fun run(
            id: String,
            row: Int,
            text: String,
            heading: Boolean = false,
        ): TextRun {
            val s = SourceMap.stripWithColours(text)
            return TextRun(
                id,
                2,
                row,
                row,
                53f,
                display = s.display,
                colourSpans = s.spans,
                sourceMap = s.map,
                sourceStrokeIds = emptyList(),
                heading = heading,
            )
        }
        return Page(
            listOf(stroke("ink", Tool.BALLPOINT, 1300f)),
            false,
            text =
                listOf(
                    run("L1", 0, "# Shopping list", heading = true),
                    run("L2", 1, "buy **milk** and bread"),
                    run("L3", 2, "call [[red]]Anna[[/]] at five"),
                ),
            pendingSegments = listOf(PendingSegment("c", "p0-r5-0", 5, 100f, 400f, emptyList())),
        )
    }

    private fun captureNotebookPage(name: String) {
        val page = notebookPage()
        val layer = TextLayer(NotebookGeometry(1404, 1872)).render(page, null)
        compose.setContent {
            MaterialTheme {
                BoardPreviewCanvas(Canvas(1404, 1872, 300f), page, Modifier.fillMaxSize(), textLayer = layer)
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun notebook_page_with_text_portrait() = captureNotebookPage("notebook_page_with_text_portrait")

    @Test
    @Config(qualifiers = "w900dp-h480dp-xhdpi")
    fun notebook_page_with_text_landscape() = captureNotebookPage("notebook_page_with_text_landscape")

    /** A notebook page with a text run and an image region around a drawing; [applied] once a version used it. */
    private fun captureRegionPage(
        name: String,
        applied: Boolean,
    ) {
        val g = NotebookGeometry(1404, 1872)
        val s = SourceMap.stripWithColours("A sketch of the house:")
        val run = TextRun("L1", 2, 1, 1, 53f, display = s.display, colourSpans = s.spans, sourceMap = s.map, sourceStrokeIds = emptyList())
        val region =
            Stroke(
                "r",
                Tool.IMAGE_REGION,
                BoardViewModel.SHAPE_WIDTH,
                listOf(Point(150f, g.rowTop(3).toFloat(), 1f, 0L), Point(900f, g.rowTop(9).toFloat(), 1f, 1L)),
            )
        val house =
            Stroke(
                "h",
                Tool.BALLPOINT,
                6f,
                listOf(300f to 850f, 300f to 550f, 525f to 400f, 750f to 550f, 750f to 850f, 300f to 850f).mapIndexed { i, (x, y) ->
                    Point(x, y, 0.8f, i * 10L)
                },
            )
        val page =
            Page(
                listOf(region, house),
                false,
                text = listOf(run),
                appliedRegions = if (applied) setOf("r") else emptySet(),
            )
        val layer = TextLayer(g).render(page, null)
        compose.setContent {
            MaterialTheme {
                BoardPreviewCanvas(Canvas(1404, 1872, 300f), page, Modifier.fillMaxSize(), textLayer = layer)
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun region_frame_pending() = captureRegionPage("region_frame_pending", applied = false)

    @Test
    fun region_frame_applied() = captureRegionPage("region_frame_applied", applied = true)
}
