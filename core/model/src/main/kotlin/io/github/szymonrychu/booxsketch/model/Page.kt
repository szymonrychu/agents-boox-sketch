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

    /** Appends [pending] segments (per page index) to their pages; pages that no longer exist are skipped. */
    fun addPending(pending: Map<Int, List<PendingSegment>>): Notebook =
        copy(pages = pages.mapIndexed { i, p -> pending[i]?.let { p.copy(pendingSegments = p.pendingSegments + it) } ?: p })

    /**
     * Drops the pending segments and ops of the upload [clientId] (it failed): their strokes stay visible ink and the
     * pages that had any count as changed again, so the next Send carries them (Q17 A: marks kept for a re-send).
     */
    fun dropPending(clientId: String): Notebook =
        copy(
            pages =
                pages.map { p ->
                    val segments = p.pendingSegments.filterNot { it.clientId == clientId }
                    val ops = p.pendingOps.filterNot { it.clientId == clientId }
                    if (segments.size == p.pendingSegments.size && ops.size == p.pendingOps.size) {
                        p
                    } else {
                        p.copy(pendingSegments = segments, pendingOps = ops, changedSinceSend = true)
                    }
                },
        )

    fun markSent(indices: Set<Int>): Notebook =
        copy(pages = pages.mapIndexed { idx, p -> if (idx in indices) p.copy(changedSinceSend = false) else p })
}
