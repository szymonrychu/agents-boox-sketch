package io.github.szymonrychu.booxsketch.board

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun stroke(
        id: String,
        x: Float = 10f,
        y: Float = 10f,
    ) = Stroke(id, Tool.BALLPOINT, 4f, listOf(Point(x, y, 0.5f, 0L), Point(x + 20f, y, 0.5f, 5L)))

    private fun TestScope.vm(): BoardViewModel =
        BoardViewModel(NotebookStore(File(tmp.root, "notebook.json")), this, StandardTestDispatcher(testScheduler))

    @Test
    fun autosave_restores_after_recreate() =
        runTest {
            val first = vm()
            first.onStroke(stroke("a"))
            first.addPage()
            first.onStroke(stroke("b"))
            advanceTimeBy(499)
            runCurrent()
            assertThat(File(tmp.root, "notebook.json").exists()).isFalse()
            advanceTimeBy(2)
            runCurrent()
            val second = vm()
            assertThat(second.notebook.value).isEqualTo(first.notebook.value)
            assertThat(
                second.notebook.value.pages
                    .map { it.strokes.size },
            ).containsExactly(1, 1).inOrder()
        }

    @Test
    fun page_switch_keeps_undo_per_page() =
        runTest {
            val v = vm()
            v.onStroke(stroke("a"))
            v.addPage()
            assertThat(v.canUndo.value).isFalse()
            v.onStroke(stroke("b"))
            v.selectPage(0)
            assertThat(v.canUndo.value).isTrue()
            v.undo()
            assertThat(
                v.notebook.value.pages[0]
                    .strokes,
            ).isEmpty()
            assertThat(
                v.notebook.value.pages[1]
                    .strokes
                    .map { it.id },
            ).containsExactly("b")
            v.selectPage(1)
            assertThat(v.canUndo.value).isTrue()
            assertThat(v.canRedo.value).isFalse()
        }

    @Test
    fun eraser_removes_hit_strokes_undoably() =
        runTest {
            val v = vm()
            v.onStroke(stroke("near", 10f, 10f))
            v.onStroke(stroke("far", 10f, 500f))
            v.onErase(15f, 12f, 3f)
            assertThat(
                v.notebook.value.pages[0]
                    .strokes
                    .map { it.id },
            ).containsExactly("far")
            v.undo()
            assertThat(
                v.notebook.value.pages[0]
                    .strokes
                    .map { it.id },
            ).containsExactly("near", "far").inOrder()
            v.onErase(900f, 900f, 3f)
            assertThat(v.canRedo.value).isTrue()
        }

    @Test
    fun delete_page_goes_through_undo_rekeying() =
        runTest {
            val v = vm()
            v.onStroke(stroke("a"))
            v.addPage()
            v.onStroke(stroke("b"))
            v.selectPage(1)
            v.deletePage(0)
            assertThat(
                v.notebook.value.pages
                    .single()
                    .strokes
                    .map { it.id },
            ).containsExactly("b")
            assertThat(v.canUndo.value).isTrue()
            v.undo()
            assertThat(
                v.notebook.value.pages
                    .single()
                    .strokes,
            ).isEmpty()
        }

    @Test
    fun tool_choice_clears_eraser_and_clear_is_undoable() =
        runTest {
            val v = vm()
            v.setEraser(true)
            v.setTool(Tool.MARKER)
            assertThat(v.eraser.value).isFalse()
            v.onStroke(stroke("a"))
            v.clear()
            assertThat(
                v.notebook.value.pages[0]
                    .strokes,
            ).isEmpty()
            v.undo()
            assertThat(
                v.notebook.value.pages[0]
                    .strokes,
            ).hasSize(1)
        }

    @Test
    fun flush_persists_strokes_inside_the_debounce_window() =
        runTest {
            val v = vm()
            v.onStroke(stroke("a"))
            advanceTimeBy(100)
            runCurrent()
            assertThat(File(tmp.root, "notebook.json").exists()).isFalse()
            v.flush()
            assertThat(
                NotebookStore(File(tmp.root, "notebook.json"))
                    .load()
                    ?.pages
                    ?.get(0)
                    ?.strokes
                    ?.map { it.id },
            ).containsExactly("a")
        }

    @Test
    fun save_io_failure_is_surfaced_and_keeps_the_notebook() =
        runTest {
            val blocker = tmp.newFile("blocker")
            val v =
                BoardViewModel(
                    NotebookStore(File(blocker, "notebook.json")),
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            v.onStroke(stroke("a"))
            advanceTimeBy(501)
            runCurrent()
            assertThat(v.saveError.value).isNotNull()
            v.flush()
            assertThat(v.saveError.value).isNotNull()
            assertThat(
                v.notebook.value.pages[0]
                    .strokes,
            ).hasSize(1)
        }

    private fun shape(
        id: String,
        tool: Tool,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) = Stroke(id, tool, 4f, listOf(Point(x0, y0, 0.5f, 0L), Point(x1, y1, 0.5f, 5L)))

    private fun BoardViewModel.strokes() = notebook.value.pages[notebook.value.current].strokes

    @Test
    fun set_color_is_kept_and_clears_eraser() =
        runTest {
            val v = vm()
            assertThat(v.color.value).isEqualTo(PenColor.BLACK)
            v.setEraser(true)
            v.setColor(PenColor.PURPLE)
            assertThat(v.color.value).isEqualTo(PenColor.PURPLE)
            assertThat(v.eraser.value).isFalse()
        }

    @Test
    fun shape_drag_updates_preview_with_snap() =
        runTest {
            val v = vm()
            v.onStroke(shape("r", Tool.RECTANGLE, 100f, 100f, 300f, 200f))
            val drag = shape("a", Tool.ARROW, 500f, 500f, 310f, 205f)
            v.onShapeDrag(drag)
            val p = v.preview.value!!
            assertThat(p.points).hasSize(2)
            assertThat(p.points.last().x).isEqualTo(300f)
            assertThat(p.points.last().y).isEqualTo(200f)
        }

    @Test
    fun shape_commit_snaps_arrow_to_rectangle_and_clears_preview() =
        runTest {
            val v = vm()
            v.onStroke(shape("r", Tool.RECTANGLE, 100f, 100f, 300f, 200f))
            val drag = shape("a", Tool.ARROW, 500f, 500f, 310f, 205f)
            v.onShapeDrag(drag)
            val previewed = v.preview.value
            v.onStroke(drag)
            assertThat(v.preview.value).isNull()
            val added = v.strokes().last()
            assertThat(added.points).isEqualTo(previewed!!.points)
            assertThat(added.points.last().x).isEqualTo(300f)
            assertThat(added.points.last().y).isEqualTo(200f)
        }

    @Test
    fun tiny_shape_is_dropped_and_not_undoable() =
        runTest {
            val v = vm()
            v.onShapeDrag(shape("t", Tool.RECTANGLE, 10f, 10f, 12f, 13f))
            assertThat(v.preview.value).isNull()
            v.onStroke(shape("t", Tool.RECTANGLE, 10f, 10f, 12f, 13f))
            assertThat(v.strokes()).isEmpty()
            assertThat(v.canUndo.value).isFalse()
        }

    @Test
    fun shape_cancel_clears_preview() =
        runTest {
            val v = vm()
            v.onShapeDrag(shape("r", Tool.RECTANGLE, 100f, 100f, 300f, 200f))
            assertThat(v.preview.value).isNotNull()
            v.onShapeCancel()
            assertThat(v.preview.value).isNull()
        }

    @Test
    fun page_switch_clears_preview() =
        runTest {
            val v = vm()
            v.addPage()
            v.onShapeDrag(shape("r", Tool.RECTANGLE, 100f, 100f, 300f, 200f))
            assertThat(v.preview.value).isNotNull()
            v.selectPage(0)
            assertThat(v.preview.value).isNull()
        }

    @Test
    fun erasing_rectangle_keeps_snapped_arrow() =
        runTest {
            val v = vm()
            v.onStroke(shape("r", Tool.RECTANGLE, 100f, 100f, 300f, 200f))
            v.onStroke(shape("a", Tool.ARROW, 500f, 500f, 310f, 205f))
            v.onErase(100f, 150f, 5f)
            assertThat(v.strokes().map { it.tool }).containsExactly(Tool.ARROW)
            val arrow = v.strokes().single()
            assertThat(arrow.points.last().x).isEqualTo(300f)
        }

    @Test
    fun undo_removes_shape() =
        runTest {
            val v = vm()
            v.onStroke(shape("r", Tool.RECTANGLE, 100f, 100f, 300f, 200f))
            assertThat(v.strokes()).hasSize(1)
            v.undo()
            assertThat(v.strokes()).isEmpty()
        }

    @Test
    fun shape_tools_use_fixed_width() =
        runTest {
            val v = vm()
            v.setWidth(12f)
            assertThat(v.effectiveWidth.value).isEqualTo(12f)
            v.setTool(Tool.RECTANGLE)
            assertThat(v.effectiveWidth.value).isEqualTo(BoardViewModel.SHAPE_WIDTH)
            v.setTool(Tool.ARROW)
            assertThat(v.effectiveWidth.value).isEqualTo(BoardViewModel.SHAPE_WIDTH)
        }

    @Test
    fun width_slider_value_survives_a_shape_tool() =
        runTest {
            val v = vm()
            v.setTool(Tool.RECTANGLE)
            v.setWidth(17f)
            assertThat(v.width.value).isEqualTo(17f)
            assertThat(v.effectiveWidth.value).isEqualTo(BoardViewModel.SHAPE_WIDTH)
            v.setTool(Tool.MARKER)
            assertThat(v.effectiveWidth.value).isEqualTo(17f)
        }

    @Test
    fun committed_rectangle_has_shape_width_even_if_input_sent_other_width() =
        runTest {
            val v = vm()
            val wide = shape("r", Tool.RECTANGLE, 100f, 100f, 300f, 200f).copy(width = 20f)
            v.onStroke(wide)
            assertThat(v.strokes().single().width).isEqualTo(BoardViewModel.SHAPE_WIDTH)
        }

    @Test
    fun shape_preview_has_shape_width() =
        runTest {
            val v = vm()
            v.onShapeDrag(shape("r", Tool.ARROW, 100f, 100f, 300f, 200f).copy(width = 20f))
            assertThat(v.preview.value!!.width).isEqualTo(BoardViewModel.SHAPE_WIDTH)
        }

    @Test
    fun committed_pen_stroke_keeps_slider_width() =
        runTest {
            val v = vm()
            v.setWidth(9f)
            v.onStroke(stroke("a").copy(width = 9f))
            assertThat(v.strokes().single().width).isEqualTo(9f)
        }

    private fun TestScope.bindable(): BoardViewModel {
        val files = NotebookFiles(tmp.root)
        return BoardViewModel(files.store(null), this, StandardTestDispatcher(testScheduler), files = files)
    }

    @Test
    fun switching_conversation_swaps_notebook() =
        runTest {
            val v = bindable()
            v.bindConversation("A")
            v.onStroke(stroke("a1"))
            v.bindConversation("B")
            assertThat(v.notebook.value.conversationLocalId).isEqualTo("B")
            assertThat(v.strokes()).isEmpty()
            v.onStroke(stroke("b1"))
            assertThat(v.canUndo.value).isTrue()
            v.bindConversation("A")
            assertThat(v.notebook.value.conversationLocalId).isEqualTo("A")
            assertThat(v.strokes().map { it.id }).containsExactly("a1")
            // Undo history belongs to the notebook that made it.
            assertThat(v.canUndo.value).isFalse()
            runCurrent()
            assertThat(File(tmp.root, "notebook-B.json").readText()).contains("b1")
        }

    @Test
    fun new_conversation_starts_empty_notebook() =
        runTest {
            val v = bindable()
            v.bindConversation("A")
            v.onStroke(stroke("a1"))
            v.addPage()
            v.bindConversation("new-1")
            val n = v.notebook.value
            assertThat(n.conversationLocalId).isEqualTo("new-1")
            assertThat(n.pages).hasSize(1)
            assertThat(n.pages.single().strokes).isEmpty()
        }

    @Test
    fun unbound_notebook_is_adopted_by_its_first_conversation() =
        runTest {
            val v = bindable()
            v.onStroke(stroke("before"))
            v.bindConversation("A")
            assertThat(v.notebook.value.conversationLocalId).isEqualTo("A")
            assertThat(v.strokes().map { it.id }).containsExactly("before")
            assertThat(File(tmp.root, "notebook-A.json").exists()).isTrue()
            assertThat(File(tmp.root, "notebook.json").exists()).isFalse()
        }

    @Test
    fun changes_during_adoption_are_kept() =
        runTest {
            val v = bindable()
            v.onStroke(stroke("before"))
            // The first Send selects a new conversation (bind) and marks its pages and pending segments meanwhile.
            val bind = launch { v.bindConversation("A") }
            launch {
                v.onStroke(stroke("during"))
                v.addPending(mapOf(0 to listOf(PendingSegment("c1", "p0-r4-0", 4, 10f, 30f, listOf("before")))))
            }
            advanceUntilIdle()
            bind.join()
            val n = v.notebook.value
            assertThat(n.conversationLocalId).isEqualTo("A")
            assertThat(v.strokes().map { it.id }).containsExactly("before", "during").inOrder()
            assertThat(n.pages[0].pendingSegments.map { it.clientId }).containsExactly("c1")
        }

    @Test
    fun strokes_during_a_switch_stay_with_the_old_conversation() =
        runTest {
            val v = bindable()
            v.bindConversation("A")
            v.onStroke(stroke("a1"))
            val bind = launch { v.bindConversation("B") }
            launch { v.onStroke(stroke("a2")) }
            advanceUntilIdle()
            bind.join()
            assertThat(v.notebook.value.conversationLocalId).isEqualTo("B")
            val saved = NotebookStore(File(tmp.root, "notebook-A.json")).load()
            assertThat(saved!!.pages[0].strokes.map { it.id }).containsExactly("a1", "a2").inOrder()
        }

    @Test
    fun failed_upload_of_another_conversation_edits_its_saved_notebook() =
        runTest {
            val v = bindable()
            v.bindConversation("A")
            v.onStroke(stroke("a1"))
            v.addPending(mapOf(0 to listOf(PendingSegment("c1", "p0-r4-0", 4, 10f, 30f, listOf("a1")))))
            v.markSent(setOf(0))
            v.bindConversation("B")
            v.dropFailedUpload("c1", "A")
            val saved = checkNotNull(NotebookStore(File(tmp.root, "notebook-A.json")).load())
            assertThat(saved.pages[0].pendingSegments).isEmpty()
            assertThat(saved.pages[0].changedSinceSend).isTrue()
            assertThat(v.notebook.value.conversationLocalId).isEqualTo("B")
        }

    @Test
    fun removed_bound_conversation_is_not_saved_again() =
        runTest {
            val v = bindable()
            v.bindConversation("A")
            v.onStroke(stroke("a1"))
            runCurrent()
            NotebookFiles(tmp.root).delete("A")
            v.conversationGone("A")
            v.onStroke(stroke("x"))
            advanceUntilIdle()
            assertThat(File(tmp.root, "notebook-A.json").exists()).isFalse()
            assertThat(v.notebook.value.conversationLocalId).isNull()
        }
}
