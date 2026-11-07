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

/** The vertical centre of [s]'s bounding box, the y a row shift and the segmenter judge a stroke by. */
fun centreY(s: Stroke): Float = if (s.points.isEmpty()) 0f else (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f

/** [strokes] with every stroke centred at or below [fromY] moved down by [dy]. */
fun shiftStrokes(
    strokes: List<Stroke>,
    fromY: Float,
    dy: Float,
): List<Stroke> =
    strokes.map { s -> if (s.points.isNotEmpty() && centreY(s) >= fromY) s.copy(points = s.points.map { it.copy(y = it.y + dy) }) else s }

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

    /** Inserts [page] at [at] into [n] and re-keys the histories of the pages after it. */
    fun insertPage(
        n: Notebook,
        at: Int,
        page: Page,
    ): Notebook {
        val i = at.coerceIn(0, n.pages.size)
        val moved = histories.mapKeys { (k, _) -> if (k >= i) k + 1 else k }
        histories.clear()
        histories.putAll(moved)
        return n.insertPage(i, page)
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

    /**
     * Moves every stroke of page [page] centred at or below page y [fromY] down by [dy] page px: the whole-row shift
     * under a wrapped or inserted text line (Q16 A). It is not an undo step (the text moved with it is not in the
     * history): the page's history entries move the same way, so undo and redo stay aligned with the shifted rows. The
     * page does not count as changed since the last send.
     */
    fun shiftRows(
        n: Notebook,
        page: Int,
        fromY: Float,
        dy: Float,
    ): Notebook {
        histories[page]?.let { h ->
            for (q in listOf(h.undo, h.redo)) {
                val moved = q.map { Entry(shiftStrokes(it.before, fromY, dy), shiftStrokes(it.after, fromY, dy)) }
                q.clear()
                q.addAll(moved)
            }
        }
        return n.copy(pages = n.pages.mapIndexed { i, p -> if (i == page) p.copy(strokes = shiftStrokes(p.strokes, fromY, dy)) else p })
    }

    /** Drops the history of page [page]: its strokes moved in a way no entry can follow (rows sent to another page). */
    fun forget(page: Int) {
        histories.remove(page)
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
        copy(pages = pages.mapIndexed { i, p -> if (i == current) p.copy(strokes = strokes, changedSinceSend = true) else p })
}
