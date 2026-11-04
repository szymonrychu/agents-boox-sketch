package io.github.szymonrychu.booxsketch.model.doc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PagePackerTest {
    private fun atomic(
        h: Int,
        from: Int,
        to: Int = from,
    ) = Block(h, from, to, emptyList())

    private fun text(
        lines: Int,
        lineH: Int,
        from: Int,
        to: Int = from + lines - 1,
    ) = Block(lines * lineH, from, to, List(lines) { lineH })

    @Test
    fun empty_input_gives_one_empty_page() {
        val pages = packPages(emptyList(), 100)
        assertThat(pages).hasSize(1)
        assertThat(pages[0].slices).isEmpty()
        assertThat(pages[0].sourceLines.isEmpty()).isTrue()
    }

    @Test
    fun blocks_fill_a_page_then_start_next() {
        val pages = packPages(listOf(atomic(40, 0), atomic(40, 1), atomic(40, 2)), 100)
        assertThat(pages.map { p -> p.slices.map { it.blockIndex } }).containsExactly(listOf(0, 1), listOf(2)).inOrder()
    }

    @Test
    fun slice_offsets_accumulate() {
        val page = packPages(listOf(atomic(30, 0), atomic(20, 1), atomic(10, 2)), 100).single()
        assertThat(page.slices.map { it.offsetY }).containsExactly(0, 30, 50).inOrder()
    }

    @Test
    fun oversize_text_block_sliced_at_line_boundary_with_line_ranges() {
        // 10 lines of 30 px = 300 px over source lines 0..9, page height 100: 3 lines per page
        val pages = packPages(listOf(text(10, 30, 0)), 100)
        assertThat(pages).hasSize(4)
        assertThat(pages.map { p -> p.slices.single().let { it.fromLine to it.toLine } })
            .containsExactly(0 to 3, 3 to 6, 6 to 9, 9 to 10)
            .inOrder()
        assertThat(pages.map { it.sourceLines }).containsExactly(0..2, 3..5, 6..8, 9..9).inOrder()
        assertThat(pages.all { p -> p.slices.sumOf { it.heightPx } <= 100 }).isTrue()
    }

    @Test
    fun sliced_block_starts_in_the_remaining_space() {
        val pages = packPages(listOf(atomic(50, 0), text(10, 30, 1)), 100)
        assertThat(pages[0].slices.map { it.blockIndex }).containsExactly(0, 1).inOrder()
        assertThat(pages[0].slices[1].toLine).isEqualTo(1)
    }

    @Test
    fun oversize_atomic_block_gets_own_page_clipped() {
        val pages = packPages(listOf(atomic(20, 0), atomic(500, 1), atomic(20, 2)), 100)
        assertThat(pages).hasSize(3)
        assertThat(pages[1].slices.single().heightPx).isEqualTo(100)
        assertThat(pages[1].slices.single().blockIndex).isEqualTo(1)
        assertThat(pages[2].slices.single().blockIndex).isEqualTo(2)
    }

    @Test
    fun page_source_lines_cover_slices() {
        val pages = packPages(listOf(atomic(40, 3, 5), atomic(40, 6, 9)), 100)
        assertThat(pages.single().sourceLines).isEqualTo(3..9)
    }

    @Test
    fun deterministic_for_same_input() {
        val blocks = listOf(text(7, 30, 0), atomic(80, 7), text(3, 30, 8))
        assertThat(packPages(blocks, 100)).isEqualTo(packPages(blocks, 100))
    }
}
