package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.render.StrokeRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

private class FrameRenderer : StrokeRenderer {
    /** Per frame: (stroke ids, scale) of every draw call. */
    val frames = mutableListOf<MutableList<Pair<List<String>, Float>>>()

    override fun draw(
        canvas: Canvas,
        strokes: List<Stroke>,
        scale: Float,
        dx: Float,
        dy: Float,
    ) {
        frames.last() += strokes.map { it.id } to scale
    }
}

/** An input that hands the board scripted shape drags and commits, and records tool and colour. */
private class ScriptedInput : StrokeInput {
    val channel = Channel<StrokeEvent>(Channel.UNLIMITED)
    override val events: Flow<StrokeEvent> = channel.receiveAsFlow()
    override val inProgress = MutableStateFlow<Stroke?>(null)
    val tools = mutableListOf<Tool>()
    val colors = mutableListOf<PenColor>()

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        tools += tool
    }

    override fun setColor(color: PenColor) {
        colors += color
    }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = Unit
}

private fun drag(
    tool: Tool,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    color: PenColor = PenColor.BLACK,
) = Stroke("drag", tool, 4f, listOf(Point(x0, y0, 0.5f, 0L), Point(x1, y1, 0.5f, 10L)), color)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShapePreviewSurfaceTest {
    private val renderer = FrameRenderer()
    private val view =
        BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer).also { v ->
            v.canvasSource = {
                renderer.frames += mutableListOf<Pair<List<String>, Float>>()
                Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            }
            v.surfaceCreated(v.holder)
        }
    private val page = PageCanvas(10, 10, 1f)
    private val rect = drag(Tool.RECTANGLE, 1f, 1f, 9f, 9f).copy(id = "preview")

    @Test
    fun preview_is_drawn_over_the_page_in_page_geometry() {
        view.show(page, listOf(rect.copy(id = "page")), 0)
        view.show(page, listOf(rect.copy(id = "page")), 0, preview = rect)
        val last = renderer.frames.last()
        assertThat(last.flatMap { it.first }).containsExactly("page", "preview").inOrder()
        assertThat(last.map { it.second }.distinct()).hasSize(1)
    }

    @Test
    fun commit_replaces_the_preview_in_one_frame() {
        view.show(page, emptyList(), 0, preview = rect)
        val before = renderer.frames.size
        view.show(page, listOf(rect.copy(id = "shape")), 0, preview = null)
        assertThat(renderer.frames.size).isEqualTo(before + 1)
        assertThat(renderer.frames.last().flatMap { it.first }).containsExactly("shape")
    }

    @Test
    fun a_dropped_preview_is_cleared_from_the_board() {
        view.show(page, emptyList(), 0, preview = rect)
        view.show(page, emptyList(), 0, preview = null)
        assertThat(renderer.frames.last().flatMap { it.first }).isEmpty()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShapeDragWiringTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val input = ScriptedInput()
    private val vm by lazy {
        BoardViewModel(NotebookStore(File(tmp.newFolder(), "n.json")), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
    }

    private fun board() {
        compose.setContent { BoardScreen(vm, input) }
        compose.waitForIdle()
    }

    private fun strokes() =
        vm.notebook.value.pages[0]
            .strokes

    @Test
    fun shape_drag_shows_the_snapped_preview_and_lifting_commits_it() {
        board()
        compose.runOnIdle { vm.setTool(Tool.RECTANGLE) }
        compose.runOnIdle { input.inProgress.value = drag(Tool.RECTANGLE, 100f, 100f, 300f, 200f) }
        compose.waitForIdle()
        assertThat(vm.preview.value?.tool).isEqualTo(Tool.RECTANGLE)
        assertThat(strokes()).isEmpty()
        compose.runOnIdle { input.inProgress.value = drag(Tool.RECTANGLE, 100f, 100f, 400f, 300f) }
        compose.waitForIdle()
        val grown = vm.preview.value!!
        compose.runOnIdle {
            input.channel.trySend(StrokeEvent.Committed(drag(Tool.RECTANGLE, 100f, 100f, 400f, 300f)))
            input.inProgress.value = null
        }
        compose.waitForIdle()
        assertThat(vm.preview.value).isNull()
        assertThat(strokes().single().tool).isEqualTo(Tool.RECTANGLE)
        assertThat(strokes().single().points.map { it.x to it.y }).isEqualTo(grown.points.map { it.x to it.y })
    }

    @Test
    fun image_region_drag_preview() {
        board()
        compose.runOnIdle { vm.setTool(Tool.IMAGE_REGION) }
        compose.waitForIdle()
        assertThat(input.tools.last()).isEqualTo(Tool.IMAGE_REGION)
        // Dragged from the lower right to the upper left: the preview is normalised like a rectangle.
        compose.runOnIdle { input.inProgress.value = drag(Tool.IMAGE_REGION, 400f, 300f, 100f, 100f) }
        compose.waitForIdle()
        val preview = vm.preview.value!!
        assertThat(preview.tool).isEqualTo(Tool.IMAGE_REGION)
        assertThat(preview.points.first().x).isLessThan(preview.points.last().x)
        assertThat(preview.points.first().y).isLessThan(preview.points.last().y)
        compose.runOnIdle {
            input.channel.trySend(StrokeEvent.Committed(drag(Tool.IMAGE_REGION, 400f, 300f, 100f, 100f)))
            input.inProgress.value = null
        }
        compose.waitForIdle()
        assertThat(vm.preview.value).isNull()
        assertThat(strokes().single().tool).isEqualTo(Tool.IMAGE_REGION)
        assertThat(strokes().single().width).isEqualTo(BoardViewModel.SHAPE_WIDTH)
    }

    @Test
    fun arrow_preview_snaps_to_a_rectangle_anchor() {
        board()
        compose.runOnIdle { vm.setTool(Tool.RECTANGLE) }
        compose.runOnIdle { input.channel.trySend(StrokeEvent.Committed(drag(Tool.RECTANGLE, 100f, 100f, 300f, 300f))) }
        compose.waitForIdle()
        val box = strokes().single()
        val corner = box.points.last()
        compose.runOnIdle { vm.setTool(Tool.ARROW) }
        compose.runOnIdle { input.inProgress.value = drag(Tool.ARROW, 600f, 600f, 302f, 302f) }
        compose.waitForIdle()
        // The drag ends 2 view px from the corner; in canvas px that is still well within the snap radius.
        val tip =
            vm.preview.value!!
                .points
                .last()
        assertThat(tip.x to tip.y).isEqualTo(corner.x to corner.y)
    }

    @Test
    fun drag_cancelled_without_a_commit_drops_the_preview() {
        board()
        compose.runOnIdle { vm.setTool(Tool.ARROW) }
        compose.runOnIdle { input.inProgress.value = drag(Tool.ARROW, 100f, 100f, 300f, 200f) }
        compose.waitForIdle()
        assertThat(vm.preview.value).isNotNull()
        compose.runOnIdle {
            input.channel.trySend(StrokeEvent.Cancelled)
            input.inProgress.value = null
        }
        compose.waitForIdle()
        assertThat(vm.preview.value).isNull()
        assertThat(strokes()).isEmpty()
    }

    @Test
    fun lift_clears_the_preview_only_with_the_commit_whichever_collector_runs_first() {
        board()
        compose.runOnIdle { vm.setTool(Tool.RECTANGLE) }
        compose.runOnIdle { input.inProgress.value = drag(Tool.RECTANGLE, 100f, 100f, 400f, 300f) }
        compose.waitForIdle()
        val preview = vm.preview.value!!
        // The in-progress clear is collected before the commit: the preview must stay on the board.
        compose.runOnIdle { input.inProgress.value = null }
        compose.waitForIdle()
        assertThat(vm.preview.value).isEqualTo(preview)
        assertThat(strokes()).isEmpty()
        compose.runOnIdle { input.channel.trySend(StrokeEvent.Committed(drag(Tool.RECTANGLE, 100f, 100f, 400f, 300f))) }
        compose.waitForIdle()
        assertThat(vm.preview.value).isNull()
        assertThat(strokes().single().points.map { it.x to it.y }).isEqualTo(preview.points.map { it.x to it.y })
    }

    @Test
    fun freehand_live_strokes_never_reach_the_shape_preview() {
        board()
        compose.runOnIdle { input.inProgress.value = drag(Tool.BALLPOINT, 100f, 100f, 300f, 200f) }
        compose.waitForIdle()
        assertThat(vm.preview.value).isNull()
    }

    @Test
    fun selected_tool_and_colour_reach_the_input_and_the_committed_stroke_keeps_its_colour() {
        board()
        compose.runOnIdle {
            vm.setTool(Tool.ARROW)
            vm.setColor(PenColor.RED)
        }
        compose.waitForIdle()
        assertThat(input.tools.last()).isEqualTo(Tool.ARROW)
        assertThat(input.colors.last()).isEqualTo(PenColor.RED)
        compose.runOnIdle { input.channel.trySend(StrokeEvent.Committed(drag(Tool.ARROW, 100f, 100f, 300f, 200f, PenColor.RED))) }
        compose.waitForIdle()
        assertThat(strokes().single().color).isEqualTo(PenColor.RED)
    }
}
