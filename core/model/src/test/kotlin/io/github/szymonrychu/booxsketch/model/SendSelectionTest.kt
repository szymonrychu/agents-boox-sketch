package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SendSelectionTest {
    private val canvas = Canvas(1404, 1872, 300f)

    private fun full(changed: Boolean) = Page(listOf(Stroke("s", Tool.BRUSH, 2f, emptyList())), changed)

    private fun empty(changed: Boolean = false) = Page(emptyList(), changed)

    private fun nb(vararg pages: Page) = Notebook(canvas, pages.toList(), 0)

    @Test
    fun empty_pages_never_selected() {
        val n = nb(empty(true), full(true), empty(false))
        assertThat(selectPagesForSend(n, all = false, maxPages = 4)).isEqualTo(SendSelection.Pages(listOf(1)))
        assertThat(selectPagesForSend(n, all = true, maxPages = 4)).isEqualTo(SendSelection.Pages(listOf(1)))
    }

    @Test
    fun unchanged_pages_skipped_by_default() {
        val n = nb(full(false), full(true), full(true))
        assertThat(selectPagesForSend(n, all = SEND_ALL_BY_DEFAULT, maxPages = 4))
            .isEqualTo(SendSelection.Pages(listOf(1, 2)))
    }

    @Test
    fun send_all_includes_unchanged_non_empty() {
        val n = nb(full(false), empty(), full(true))
        assertThat(selectPagesForSend(n, all = true, maxPages = 4)).isEqualTo(SendSelection.Pages(listOf(0, 2)))
    }

    @Test
    fun nothing_changed_gives_nothing_to_send() {
        val n = nb(full(false), empty(true))
        assertThat(selectPagesForSend(n, all = false, maxPages = 4)).isEqualTo(SendSelection.NothingToSend)
        assertThat(selectPagesForSend(nb(empty()), all = true, maxPages = 4)).isEqualTo(SendSelection.NothingToSend)
    }

    @Test
    fun over_max_pages_refused() {
        val n = nb(full(true), full(true), full(true))
        assertThat(selectPagesForSend(n, all = false, maxPages = 2)).isEqualTo(SendSelection.TooManyPages(3, 2))
        assertThat(selectPagesForSend(n, all = false, maxPages = 3)).isEqualTo(SendSelection.Pages(listOf(0, 1, 2)))
    }

    @Test
    fun pages_with_only_consumed_strokes_never_selected() {
        val consumed = Page(listOf(Stroke("s", Tool.BRUSH, 2f, emptyList())), true, consumed = setOf("s"))
        assertThat(selectPagesForSend(nb(consumed, full(true)), all = true, maxPages = 4)).isEqualTo(SendSelection.Pages(listOf(1)))
    }

    private fun withRegion(changed: Boolean) =
        Page(
            listOf(Stroke("r", Tool.IMAGE_REGION, 3f, listOf(Point(100f, 100f, 1f, 0L), Point(500f, 400f, 1f, 1L)))),
            changed,
        )

    @Test
    fun an_unsent_region_counts_as_changed() {
        val n = nb(withRegion(changed = false))
        assertThat(selectPagesForSend(n, all = false, maxPages = 4)).isEqualTo(SendSelection.Pages(listOf(0)))
    }
}
