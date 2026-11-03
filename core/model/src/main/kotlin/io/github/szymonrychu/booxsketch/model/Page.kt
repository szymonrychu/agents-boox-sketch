package io.github.szymonrychu.booxsketch.model

import kotlinx.serialization.Serializable

@Serializable
data class Page(
    val strokes: List<Stroke>,
    val changedSinceSend: Boolean,
)

/** Immutable notebook: every operation returns a new instance. */
@Serializable
data class Notebook(
    val canvas: Canvas,
    val pages: List<Page>,
    val current: Int,
) {
    /** Appends an empty page and selects it. */
    fun addPage(): Notebook = copy(pages = pages + Page(emptyList(), false), current = pages.size)

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
