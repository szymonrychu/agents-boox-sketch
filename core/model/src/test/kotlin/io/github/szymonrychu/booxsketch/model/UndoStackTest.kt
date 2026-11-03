package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UndoStackTest {
    private fun stroke(id: String) = Stroke(id, Tool.FOUNTAIN, 3f, listOf(Point(1f, 2f, 0.5f, 10L)))

    private fun notebook() = Notebook(Canvas(1404, 1872, 300f), listOf(Page(emptyList(), false)), 0)

    @Test
    fun add_undo_redo_roundtrip() {
        val stack = UndoStack()
        val added = stack.apply(notebook(), Command.AddStroke(stroke("a")))
        assertThat(added.pages[0].strokes.map { it.id }).containsExactly("a")
        assertThat(added.pages[0].changedSinceSend).isTrue()
        assertThat(stack.canUndo).isTrue()
        val undone = stack.undo(added)
        assertThat(undone.pages[0].strokes).isEmpty()
        assertThat(stack.canRedo).isTrue()
        val redone = stack.redo(undone)
        assertThat(redone.pages[0].strokes.map { it.id }).containsExactly("a")
        assertThat(stack.canRedo).isFalse()
    }

    @Test
    fun new_command_clears_redo() {
        val stack = UndoStack()
        val n = stack.undo(stack.apply(notebook(), Command.AddStroke(stroke("a"))))
        assertThat(stack.canRedo).isTrue()
        stack.apply(n, Command.AddStroke(stroke("b")))
        assertThat(stack.canRedo).isFalse()
    }

    @Test
    fun clear_is_undoable() {
        val stack = UndoStack()
        var n = stack.apply(notebook(), Command.AddStroke(stroke("a")))
        n = stack.apply(n, Command.AddStroke(stroke("b")))
        n = stack.apply(n, Command.Clear)
        assertThat(n.pages[0].strokes).isEmpty()
        n = stack.undo(n)
        assertThat(n.pages[0].strokes.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun erase_removes_only_given_ids() {
        val stack = UndoStack()
        var n = stack.apply(notebook(), Command.AddStroke(stroke("a")))
        n = stack.apply(n, Command.AddStroke(stroke("b")))
        n = stack.apply(n, Command.EraseStrokes(setOf("a")))
        assertThat(n.pages[0].strokes.map { it.id }).containsExactly("b")
    }

    @Test
    fun undo_on_other_page_does_not_touch_current() {
        val stack = UndoStack()
        var n = stack.apply(notebook().addPage().select(0), Command.AddStroke(stroke("a")))
        n = n.select(1)
        assertThat(stack.undo(n)).isEqualTo(n)
        assertThat(stack.canUndo).isFalse()
        n = stack.apply(n, Command.AddStroke(stroke("b")))
        n = stack.undo(n)
        assertThat(n.pages[0].strokes.map { it.id }).containsExactly("a")
        assertThat(n.pages[1].strokes).isEmpty()
    }

    @Test
    fun stale_history_after_page_deletion_is_ignored() {
        val stack = UndoStack()
        val n = stack.apply(notebook().addPage().select(0), Command.AddStroke(stroke("a")))
        val deleted = n.deletePage(0)
        assertThat(stack.undo(deleted)).isEqualTo(deleted)
    }
}
