package io.github.szymonrychu.booxsketch.model

sealed interface Command {
    data class AddStroke(
        val stroke: Stroke,
    ) : Command

    data class EraseStrokes(
        val ids: Set<String>,
    ) : Command

    data object Clear : Command
}

/**
 * Undo/redo history kept per page index. An entry is only honoured while the page still holds
 * the strokes the entry produced, so history made stale by page deletion is ignored.
 * [canUndo] and [canRedo] describe the page of the most recent call.
 */
class UndoStack {
    private class Entry(
        val before: List<Stroke>,
        val after: List<Stroke>,
    )

    private class History(
        val undo: ArrayDeque<Entry> = ArrayDeque(),
        val redo: ArrayDeque<Entry> = ArrayDeque(),
    )

    private val histories = mutableMapOf<Int, History>()
    private var active = 0

    val canUndo: Boolean get() = histories[active]?.undo?.isNotEmpty() == true
    val canRedo: Boolean get() = histories[active]?.redo?.isNotEmpty() == true

    fun apply(
        n: Notebook,
        c: Command,
    ): Notebook {
        active = n.current
        val before = n.pages[n.current].strokes
        val after =
            when (c) {
                is Command.AddStroke -> before + c.stroke
                is Command.EraseStrokes -> before.filterNot { it.id in c.ids }
                Command.Clear -> emptyList()
            }
        if (after == before) return n
        val h = histories.getOrPut(n.current) { History() }
        h.undo.addLast(Entry(before, after))
        h.redo.clear()
        return n.withStrokes(after)
    }

    fun undo(n: Notebook): Notebook {
        active = n.current
        val h = histories[n.current] ?: return n
        val e = h.undo.lastOrNull() ?: return n
        if (n.pages[n.current].strokes != e.after) {
            h.undo.clear()
            h.redo.clear()
            return n
        }
        h.undo.removeLast()
        h.redo.addLast(e)
        return n.withStrokes(e.before)
    }

    fun redo(n: Notebook): Notebook {
        active = n.current
        val h = histories[n.current] ?: return n
        val e = h.redo.lastOrNull() ?: return n
        if (n.pages[n.current].strokes != e.before) {
            h.undo.clear()
            h.redo.clear()
            return n
        }
        h.redo.removeLast()
        h.undo.addLast(e)
        return n.withStrokes(e.after)
    }

    private fun Notebook.withStrokes(strokes: List<Stroke>): Notebook =
        copy(pages = pages.mapIndexed { i, p -> if (i == current) Page(strokes, true) else p })
}
