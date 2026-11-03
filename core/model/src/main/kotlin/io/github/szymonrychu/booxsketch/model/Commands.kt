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
 * Undo/redo history kept per page index. Delete pages through [deletePage] so histories follow
 * their page. An entry is only honoured while the page still holds the strokes the entry
 * produced, as a second guard against stale history.
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

    /** True when the selected page of [n] has something to undo. */
    fun canUndo(n: Notebook): Boolean = histories[n.current]?.undo?.isNotEmpty() == true

    /** True when the selected page of [n] has something to redo. */
    fun canRedo(n: Notebook): Boolean = histories[n.current]?.redo?.isNotEmpty() == true

    /** Deletes page [i] of [n] and re-keys the histories so each follows its page. */
    fun deletePage(
        n: Notebook,
        i: Int,
    ): Notebook {
        if (i !in n.pages.indices) return n
        val moved = mutableMapOf<Int, History>()
        if (n.pages.size > 1) {
            for ((k, h) in histories) {
                when {
                    k < i -> moved[k] = h
                    k > i -> moved[k - 1] = h
                }
            }
        }
        histories.clear()
        histories.putAll(moved)
        return n.deletePage(i)
    }

    fun apply(
        n: Notebook,
        c: Command,
    ): Notebook {
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
