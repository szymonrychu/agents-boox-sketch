package io.github.szymonrychu.booxsketch.model

import io.github.szymonrychu.booxsketch.model.notebook.ImageRegions

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
 * Picks the pages to upload, ascending. Pages without visible strokes (consumed ink is not visible) are never
 * selected. With [all] false only pages with changedSinceSend; with [all] true every non-empty page. More than
 * [maxPages] is refused, never truncated. With [regionsSendable] (a spec version exists to base image ops on) a page with
 * an image region no op has carried yet counts as changed, so the region is sent as an op after the first version.
 */
fun selectPagesForSend(
    n: Notebook,
    all: Boolean,
    maxPages: Int,
    regionsSendable: Boolean = false,
): SendSelection {
    val indices =
        n.pages
            .withIndex()
            .filter { (_, p) ->
                p.visibleStrokes.isNotEmpty() &&
                    (all || p.changedSinceSend || (regionsSendable && ImageRegions.unsent(p).isNotEmpty()))
            }.map { it.index }
    return when {
        indices.isEmpty() -> SendSelection.NothingToSend
        indices.size > maxPages -> SendSelection.TooManyPages(indices.size, maxPages)
        else -> SendSelection.Pages(indices)
    }
}
