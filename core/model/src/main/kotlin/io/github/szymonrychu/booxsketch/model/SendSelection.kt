package io.github.szymonrychu.booxsketch.model

/** Default for the Send button (D47 B): only pages changed since the last send. */
const val SEND_ALL_BY_DEFAULT = false

sealed interface SendSelection {
    data class Pages(
        val indices: List<Int>,
    ) : SendSelection

    data object NothingToSend : SendSelection

    data class TooManyPages(
        val count: Int,
        val max: Int,
    ) : SendSelection
}

/**
 * Picks the pages to upload, ascending. Empty pages are never selected. With [all] false only
 * pages with changedSinceSend; with [all] true every non-empty page. More than [maxPages] is
 * refused, never truncated.
 */
fun selectPagesForSend(
    n: Notebook,
    all: Boolean,
    maxPages: Int,
): SendSelection {
    val indices =
        n.pages
            .withIndex()
            .filter { (_, p) -> p.strokes.isNotEmpty() && (all || p.changedSinceSend) }
            .map { it.index }
    return when {
        indices.isEmpty() -> SendSelection.NothingToSend
        indices.size > maxPages -> SendSelection.TooManyPages(indices.size, maxPages)
        else -> SendSelection.Pages(indices)
    }
}
