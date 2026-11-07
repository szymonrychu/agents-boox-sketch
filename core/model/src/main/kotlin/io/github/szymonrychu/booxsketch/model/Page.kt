package io.github.szymonrychu.booxsketch.model

import io.github.szymonrychu.booxsketch.model.notebook.PendingOp
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import kotlinx.serialization.Serializable

@Serializable
data class Page(
    val strokes: List<Stroke>,
    val changedSinceSend: Boolean,
    /** Spec lines rendered on this page's rows. */
    val text: List<TextRun> = emptyList(),
    /** Ids of strokes a spec version used; hidden, never drawn, never exported, kept for undo bookkeeping. */
    val consumed: Set<String> = emptySet(),
    val pendingSegments: List<PendingSegment> = emptyList(),
    val pendingOps: List<PendingOp> = emptyList(),
) {
    /** The strokes that are drawn and exported: [strokes] minus [consumed]. */
    val visibleStrokes: List<Stroke> get() = if (consumed.isEmpty()) strokes else strokes.filter { it.id !in consumed }
}

/** Immutable notebook: every operation returns a new instance. */
@Serializable
data class Notebook(
    val canvas: Canvas,
    val pages: List<Page>,
    val current: Int,
    /** The conversation this board belongs to (one notebook per conversation); null until bound. */
    val conversationLocalId: String? = null,
    /** The newest spec version whose layout was placed on the pages; null before the first one. */
    val appliedSpecVersion: Int? = null,
) {
    /** Appends an empty page and selects it. */
    fun addPage(): Notebook = copy(pages = pages + Page(emptyList(), false), current = pages.size)

    /** Inserts [page] at index [at] (0..size); the selected page stays selected. */
    fun insertPage(
        at: Int,
        page: Page,
    ): Notebook {
        val i = at.coerceIn(0, pages.size)
        val list = pages.toMutableList().apply { add(i, page) }
        return copy(pages = list, current = if (current >= i) current + 1 else current)
    }

    /** Removes page [i]; the notebook always keeps at least one page. */
    fun deletePage(i: Int): Notebook {
        if (i !in pages.indices) return this
        if (pages.size == 1) return copy(pages = listOf(Page(emptyList(), false)), current = 0)
        val remaining = pages.filterIndexed { idx, _ -> idx != i }
        val next =
            when {
                i < current -> current - 1
                else -> current.coerceAtMost(remaining.lastIndex)
            }
        return copy(pages = remaining, current = next)
    }

    fun select(i: Int): Notebook = if (i in pages.indices) copy(current = i) else this

    fun markSent(indices: Set<Int>): Notebook =
        copy(pages = pages.mapIndexed { idx, p -> if (idx in indices) p.copy(changedSinceSend = false) else p })
}
