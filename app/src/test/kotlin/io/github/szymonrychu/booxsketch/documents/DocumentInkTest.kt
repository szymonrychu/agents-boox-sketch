package io.github.szymonrychu.booxsketch.documents

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DocumentInkTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val canvas = Canvas(400, 600, 300f)

    @After
    fun tearDown() = scope.cancel()

    private fun ink() = DocumentInk(AnnotationStore(tmp.root), scope, Dispatchers.Unconfined)

    private fun stroke(id: String) = Stroke(id, Tool.BALLPOINT, 4f, listOf(Point(10f, 10f, 0.5f, 0), Point(90f, 80f, 0.5f, 10)))

    @Test
    fun ink_survives_close_and_reopen() =
        runBlocking<Unit> {
            val first = ink().board("c1", 2, 3, canvas)
            first.selectPage(1)
            first.onStroke(stroke("a"))
            first.flush()
            val reopened = ink().board("c1", 2, 3, canvas)
            assertThat(reopened.notebook.value.pages).hasSize(3)
            assertThat(
                reopened.notebook.value.pages[1]
                    .strokes
                    .map { it.id },
            ).containsExactly("a")
            assertThat(reopened.notebook.value.canvas).isEqualTo(canvas)
        }

    @Test
    fun ink_is_stored_per_version() =
        runBlocking<Unit> {
            val ink = ink()
            val v1 = ink.board("c1", 1, 2, canvas)
            val v2 = ink.board("c1", 2, 2, canvas)
            v1.onStroke(stroke("only-v1"))
            v1.flush()
            assertThat(
                v2.notebook.value.pages[0]
                    .strokes,
            ).isEmpty()
            assertThat(ink().saved("c1", 2)?.pages?.all { it.strokes.isEmpty() }).isTrue()
            assertThat(
                ink()
                    .saved("c1", 1)!!
                    .pages[0]
                    .strokes
                    .map { it.id },
            ).containsExactly("only-v1")
        }

    @Test
    fun older_version_shows_saved_ink_without_creating_a_board() =
        runBlocking<Unit> {
            val writer = ink().board("c1", 1, 1, canvas)
            writer.onStroke(stroke("old"))
            writer.flush()
            val reader = ink()
            assertThat(
                reader
                    .saved("c1", 1)!!
                    .pages[0]
                    .strokes
                    .map { it.id },
            ).containsExactly("old")
            assertThat(reader.saved("c1", 9)).isNull()
        }

    @Test
    fun new_version_leaves_old_draft_untouched() =
        runBlocking<Unit> {
            val ink = ink()
            val old = ink.board("c1", 1, 1, canvas)
            old.onStroke(stroke("keep"))
            old.flush()
            ink.board("c1", 2, 1, canvas).onStroke(stroke("new"))
            assertThat(
                old.notebook.value.pages[0]
                    .strokes
                    .map { it.id },
            ).containsExactly("keep")
        }

    @Test
    fun undo_redo_eraser_colour_shapes_work_on_document_page() =
        runBlocking<Unit> {
            val board = ink().board("c1", 1, 1, canvas)
            board.setColor(PenColor.RED)
            board.onStroke(stroke("a"))
            board.undo()
            assertThat(
                board.notebook.value.pages[0]
                    .strokes,
            ).isEmpty()
            board.redo()
            assertThat(
                board.notebook.value.pages[0]
                    .strokes,
            ).hasSize(1)
            board.onErase(50f, 45f, 20f)
            assertThat(
                board.notebook.value.pages[0]
                    .strokes,
            ).isEmpty()
            board.undo()
            board.setTool(Tool.RECTANGLE)
            board.onStroke(
                stroke("drag").copy(tool = Tool.RECTANGLE, points = listOf(Point(50f, 50f, 0.5f, 0), Point(200f, 160f, 0.5f, 5))),
            )
            assertThat(
                board.notebook.value.pages[0]
                    .strokes
                    .last()
                    .tool,
            ).isEqualTo(Tool.RECTANGLE)
        }

    @Test
    fun a_mismatching_saved_notebook_is_replaced_by_one_matching_the_pages() =
        runBlocking<Unit> {
            ink().board("c1", 1, 2, canvas)
            val again = ink().board("c1", 1, 4, canvas)
            assertThat(again.notebook.value.pages).hasSize(4)
        }
}
