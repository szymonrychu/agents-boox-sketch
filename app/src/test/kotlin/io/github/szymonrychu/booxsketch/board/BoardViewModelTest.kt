package io.github.szymonrychu.booxsketch.board

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
}
