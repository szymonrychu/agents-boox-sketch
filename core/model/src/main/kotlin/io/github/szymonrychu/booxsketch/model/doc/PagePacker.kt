package io.github.szymonrychu.booxsketch.model.doc

/**
 * A measured top-level block of a document. [lineHeights] lists the rendered text lines of a block that may be cut
 * between lines and is empty for atomic blocks (images, tables). [lineStart] to [lineEnd] is the source line range.
 */
data class Block(
    val heightPx: Int,
    val lineStart: Int,
    val lineEnd: Int,
    val lineHeights: List<Int>,
)

/** The part of block [blockIndex] on a page: rendered lines [fromLine] until [toLine] (0 and 0 for atomic), placed at [offsetY]. */
data class PageSlice(
    val blockIndex: Int,
    val fromLine: Int,
    val toLine: Int,
    val offsetY: Int,
    val heightPx: Int,
)

data class DocPage(
    val slices: List<PageSlice>,
    val sourceLines: IntRange,
)

/**
 * Packs [blocks] into pages of [pageHeightPx] in one forward pass. Blocks keep their order; one that fits the rest of
 * the page stays, otherwise a text block taller than a page is cut at line boundaries and an atomic one taller than a
 * page gets a page of its own, clipped. An empty input yields one empty page.
 */
fun packPages(
    blocks: List<Block>,
    pageHeightPx: Int,
): List<DocPage> {
    require(pageHeightPx > 0) { "pageHeightPx must be positive" }
    val packer = Packer(pageHeightPx)
    blocks.forEachIndexed { index, block -> packer.place(index, block) }
    return packer.finish()
}

private class Packer(
    private val pageHeight: Int,
) {
    private val pages = ArrayList<DocPage>()
    private var slices = ArrayList<PageSlice>()
    private var sources = ArrayList<IntRange>()
    private var used = 0

    private fun closePage() {
        if (slices.isEmpty()) return
        pages += DocPage(slices, sources.minOf { it.first }..sources.maxOf { it.last })
        slices = ArrayList()
        sources = ArrayList()
        used = 0
    }

    private fun add(
        index: Int,
        from: Int,
        to: Int,
        height: Int,
        source: IntRange,
    ) {
        slices += PageSlice(index, from, to, used, height)
        sources += source
        used += height
    }

    fun place(
        index: Int,
        block: Block,
    ) {
        val whole = block.lineStart..block.lineEnd
        when {
            block.heightPx <= pageHeight - used -> {
                add(index, 0, block.lineHeights.size, block.heightPx, whole)
            }

            block.heightPx <= pageHeight -> {
                closePage()
                add(index, 0, block.lineHeights.size, block.heightPx, whole)
            }

            block.lineHeights.isEmpty() -> {
                closePage()
                add(index, 0, 0, pageHeight, whole)
                closePage()
            }

            else -> {
                sliceText(index, block)
            }
        }
    }

    private fun sliceText(
        index: Int,
        block: Block,
    ) {
        val lines = block.lineHeights
        val extra = block.heightPx - lines.sum()
        var from = 0
        while (from < lines.size) {
            var to = from
            var h = 0
            while (to < lines.size && h + lines[to] <= pageHeight - used) {
                h += lines[to]
                to++
            }
            if (to == from) {
                if (used > 0) {
                    closePage()
                    continue
                }
                h = minOf(lines[from], pageHeight) // a line taller than a page: clipped, never dropped
                to = from + 1
            }
            val last = to == lines.size
            add(index, from, to, if (last) minOf(h + extra, pageHeight - used).coerceAtLeast(h) else h, sourceOf(block, from, to))
            from = to
            if (!last) closePage()
        }
    }

    private fun sourceOf(
        block: Block,
        from: Int,
        to: Int,
    ): IntRange {
        val span = block.lineEnd - block.lineStart + 1
        val n = block.lineHeights.size
        val a = block.lineStart + from * span / n
        val b = block.lineStart + ((to * span + n - 1) / n) - 1
        return a..maxOf(a, minOf(b, block.lineEnd))
    }

    fun finish(): List<DocPage> {
        closePage()
        return pages.ifEmpty { listOf(DocPage(emptyList(), IntRange.EMPTY)) }
    }
}
