package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.util.Log
import io.github.szymonrychu.booxsketch.model.doc.packPages
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Lays a markdown document out as fixed-width pages of bitmaps. Top-level blocks are measured with
 * [android.text.StaticLayout] at [DocumentLayout.widthPx] and drawn onto page bitmaps by the page packer, so the view
 * and the export share the exact same pixels. [mermaid] draws diagram blocks; without it, or when it returns null, the
 * source is shown as code text.
 */
class MarkdownPaginator(
    private val layout: DocumentLayout,
    private val mermaid: BlockImage? = null,
) {
    private val styles = TextStyles(layout)
    private val flavour = GFMFlavourDescriptor()

    /** The text of every text block of [markdown] as laid out (markers resolved), for tests. */
    internal fun textBlocks(markdown: String): List<String> = BlockParser(markdown).blocks().filterIsInstance<TextBlock>().map { it.text }

    fun paginate(markdown: String): List<RenderedPage> {
        val parser = BlockParser(markdown)
        val blocks = parser.blocks()
        val usable = (layout.pageHeightPx - 2 * PAGE_MARGIN_V).coerceAtLeast(1)
        return packPages(blocks.map { it.block }, usable).map { page ->
            val bitmap = Bitmap.createBitmap(layout.widthPx, layout.pageHeightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            for (slice in page.slices) {
                canvas.save()
                canvas.translate(TextStyles.MARGIN_PX.toFloat(), (PAGE_MARGIN_V + slice.offsetY).toFloat())
                canvas.clipRect(0, 0, styles.textWidth, slice.heightPx)
                blocks[slice.blockIndex].draw(canvas, slice.fromLine, slice.toLine)
                canvas.restore()
            }
            RenderedPage(bitmap, page.sourceLines, page.slices.any { it.blockIndex in parser.failedDiagrams })
        }
    }

    private inner class BlockParser(
        private val markdown: String,
    ) {
        private val lineStarts: IntArray = lineStartOffsets(markdown)
        private val frontMatterEnd = frontMatterEnd(markdown)

        /** Indexes (in [blocks]) of diagram blocks that fell back to code text. */
        val failedDiagrams = HashSet<Int>()
        private var fenceFailed = false

        fun blocks(): List<RenderedBlock> {
            val out = ArrayList<RenderedBlock>()
            if (frontMatterEnd > 0) {
                val text = markdown.substring(0, frontMatterEnd).trimEnd('\n')
                out += codeBlock(text, lineOf(0), lineOf(frontMatterEnd - 1))
            }
            // Front matter is blanked (newlines kept) so offsets and line numbers stay those of the source.
            val body = if (frontMatterEnd > 0) blank(markdown, frontMatterEnd) else markdown
            val root = MarkdownParser(flavour).buildMarkdownTreeFromString(body)
            for (node in root.children) {
                fenceFailed = false
                topLevel(node, body)?.let {
                    if (fenceFailed) failedDiagrams += out.size
                    out += it
                }
            }
            return out
        }

        private fun topLevel(
            node: ASTNode,
            text: String,
        ): RenderedBlock? {
            val type = node.type
            if (type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.WHITE_SPACE) return null
            val from = lineOf(node.startOffset)
            val to = lineOf((node.endOffset - 1).coerceAtLeast(node.startOffset))
            val level = HEADING_LEVELS[type]
            return when {
                level != null -> {
                    heading(node, text, level)
                }

                type == MarkdownElementTypes.PARAGRAPH -> {
                    textBlock(inline(node, text), from, to)
                }

                type == MarkdownElementTypes.UNORDERED_LIST || type == MarkdownElementTypes.ORDERED_LIST -> {
                    textBlock(list(node, text, 0), from, to)
                }

                type == MarkdownElementTypes.BLOCK_QUOTE -> {
                    quote(node, text, from, to)
                }

                type == MarkdownElementTypes.CODE_FENCE -> {
                    fence(node, text, from, to)
                }

                type == MarkdownElementTypes.CODE_BLOCK -> {
                    codeBlock(node.getTextInNode(text).toString().trimEnd('\n'), from, to)
                }

                type == GFMElementTypes.TABLE -> {
                    table(node, text, from, to)
                }

                type == MarkdownTokenTypes.HORIZONTAL_RULE -> {
                    RuleBlock(styles.textWidth, from, to)
                }

                else -> {
                    textBlock(SpannableStringBuilder(node.getTextInNode(text).toString().trim()), from, to)
                }
            }
        }

        private fun heading(
            node: ASTNode,
            text: String,
            level: Int,
        ): RenderedBlock {
            val content =
                node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT }
            val s = if (content != null) inline(content, text) else SpannableStringBuilder()
            trim(s)
            styles.bold(s, 0, s.length)
            styles.size(s, 0, s.length, styles.headingSize(level))
            return textBlock(
                s,
                lineOf(node.startOffset),
                lineOf((node.endOffset - 1).coerceAtLeast(node.startOffset)),
                styles.headingSize(level),
            )
        }

        private fun quote(
            node: ASTNode,
            text: String,
            from: Int,
            to: Int,
        ): RenderedBlock {
            val s = SpannableStringBuilder()
            for (child in node.children) {
                if (child.type == MarkdownElementTypes.PARAGRAPH) {
                    if (s.isNotEmpty()) s.append('\n')
                    s.append(inline(child, text))
                }
            }
            if (s.isEmpty()) s.append(node.getTextInNode(text).toString().trim())
            styles.italic(s, 0, s.length)
            styles.indent(s, QUOTE_INDENT, QUOTE_INDENT)
            return textBlock(s, from, to)
        }

        private fun fence(
            node: ASTNode,
            text: String,
            from: Int,
            to: Int,
        ): RenderedBlock {
            val lang =
                node.children
                    .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                    ?.getTextInNode(text)
                    ?.toString()
                    ?.trim()
            val start = node.children.indexOfFirst { it.type == MarkdownTokenTypes.EOL }
            val end =
                node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_END }.let {
                    if (it <
                        0
                    ) {
                        node.children.size
                    } else {
                        it
                    }
                }
            val code =
                if (start < 0 || start + 1 > end) {
                    ""
                } else {
                    node.children
                        .subList(start + 1, end)
                        .joinToString("") { it.getTextInNode(text) }
                        .trimEnd('\n')
                }
            if (lang.equals("mermaid", ignoreCase = true)) {
                val hook = mermaid
                if (hook != null) {
                    val result = diagramResult(hook, code)
                    if (result is DiagramResult.Image) return ImageBlock(fit(result.bitmap), styles.spacing, from, to)
                    fenceFailed = true
                    return captionedCodeBlock(code, (result as DiagramResult.Failed).failure.caption, from, to)
                }
            }
            return codeBlock(code, from, to)
        }

        private fun table(
            node: ASTNode,
            text: String,
            from: Int,
            to: Int,
        ): RenderedBlock {
            val rows =
                node.getTextInNode(text).lines().filterNot { line ->
                    line.isNotBlank() && line.all { it == '|' || it == '-' || it == ':' || it == ' ' }
                }
            val s = SpannableStringBuilder(rows.joinToString("\n") { it.trim() })
            styles.mono(s, 0, s.length)
            return TextBlock(styles.measure(s, styles.paint(styles.body * TABLE_SCALE, mono = true)), styles.spacing, from, to)
        }

        @Suppress("TooGenericExceptionCaught") // a renderer failure of any kind is the captioned text fallback
        private fun diagramResult(
            hook: BlockImage,
            code: String,
        ): DiagramResult =
            try {
                if (hook is DiagramRenderer) {
                    hook.renderDiagram(code, styles.textWidth)
                } else {
                    hook.render(code, styles.textWidth)?.let { DiagramResult.Image(it) }
                        ?: DiagramResult.Failed(DiagramFailure.NoImage)
                }
            } catch (e: Exception) {
                Log.w(DiagramFailure.TAG, "diagram renderer threw", e)
                DiagramResult.Failed(DiagramFailure.Crashed(e.message ?: e.javaClass.simpleName))
            }

        /** The diagram source as code, then a one-line caption saying why it is not a picture. */
        private fun captionedCodeBlock(
            code: String,
            caption: String,
            from: Int,
            to: Int,
        ): RenderedBlock {
            val s = SpannableStringBuilder(code.ifEmpty { " " })
            styles.mono(s, 0, s.length)
            val start = s.length
            s.append('\n').append(caption)
            styles.italic(s, start + 1, s.length)
            return TextBlock(styles.measure(s, styles.paint(styles.body * CODE_SCALE, mono = true)), styles.spacing, from, to)
        }

        private fun codeBlock(
            code: String,
            from: Int,
            to: Int,
        ): RenderedBlock {
            val s = SpannableStringBuilder(code.ifEmpty { " " })
            styles.mono(s, 0, s.length)
            return TextBlock(styles.measure(s, styles.paint(styles.body * CODE_SCALE, mono = true)), styles.spacing, from, to)
        }

        private fun textBlock(
            s: SpannableStringBuilder,
            from: Int,
            to: Int,
            size: Float = styles.body,
        ): RenderedBlock {
            trim(s)
            if (s.isEmpty()) s.append(' ')
            styles.colourMarkers(s)
            return TextBlock(styles.measure(s, styles.paint(size)), styles.spacing, from, to)
        }

        private fun list(
            node: ASTNode,
            text: String,
            depth: Int,
        ): SpannableStringBuilder {
            val out = SpannableStringBuilder()
            var number = 0
            for (item in node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }) {
                number++
                val marker =
                    if (node.type == MarkdownElementTypes.ORDERED_LIST) {
                        item.children
                            .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                            ?.getTextInNode(text)
                            ?.toString()
                            ?.trim()
                            ?: "$number."
                    } else {
                        "-"
                    }
                val line = SpannableStringBuilder("$marker ")
                var nested: ASTNode? = null
                for (child in item.children) {
                    when (child.type) {
                        MarkdownElementTypes.PARAGRAPH -> line.append(inline(child, text))
                        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> nested = child
                        else -> Unit
                    }
                }
                trim(line)
                val start = out.length
                out.append(line).append('\n')
                val first = depth * LIST_INDENT
                out.setSpan(
                    android.text.style.LeadingMarginSpan
                        .Standard(first, first + LIST_HANG),
                    start,
                    out.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                nested?.let { out.append(list(it, text, depth + 1)) }
            }
            return out
        }

        /** The inline content of [node] with markers dropped; soft breaks become spaces. */
        private fun inline(
            node: ASTNode,
            text: String,
        ): SpannableStringBuilder {
            val out = SpannableStringBuilder()
            appendInline(node, text, out)
            return out
        }

        private fun appendInline(
            node: ASTNode,
            text: String,
            out: SpannableStringBuilder,
        ) {
            val type: IElementType = node.type
            val style = INLINE_STYLES[type]
            when {
                type == MarkdownTokenTypes.EOL -> {
                    out.append(' ')
                }

                type == MarkdownTokenTypes.EMPH || type == MarkdownTokenTypes.BACKTICK -> {
                    Unit
                }

                node.children.isEmpty() -> {
                    appendLeaf(node, text, out)
                }

                style != null -> {
                    styled(node, text, out) { s, a, b -> style(styles, s, a, b) }
                }

                type in LINK_TYPES -> {
                    val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    styled(label ?: node, text, out) { s, a, b -> styles.underline(s, a, b) }
                }

                type == MarkdownElementTypes.IMAGE -> {
                    out.append("[image]")
                }

                else -> {
                    node.children.forEach { appendInline(it, text, out) }
                }
            }
        }

        private fun appendLeaf(
            node: ASTNode,
            text: String,
            out: SpannableStringBuilder,
        ) {
            val bracket = node.type == MarkdownTokenTypes.LBRACKET || node.type == MarkdownTokenTypes.RBRACKET
            if (!bracket || node.parent?.type != MarkdownElementTypes.LINK_TEXT) out.append(node.getTextInNode(text))
        }

        private fun styled(
            node: ASTNode,
            text: String,
            out: SpannableStringBuilder,
            apply: (SpannableStringBuilder, Int, Int) -> Unit,
        ) {
            val start = out.length
            node.children.forEach { appendInline(it, text, out) }
            if (out.length > start) apply(out, start, out.length)
        }

        private fun lineOf(offset: Int): Int {
            val i = lineStarts.binarySearch(offset)
            return if (i >= 0) i else -i - 2
        }
    }

    private fun fit(bitmap: Bitmap): Bitmap {
        val maxH = layout.pageHeightPx - 2 * PAGE_MARGIN_V
        if (bitmap.width <= styles.textWidth && bitmap.height <= maxH) return bitmap
        val scale = minOf(styles.textWidth.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        val HEADING_LEVELS: Map<IElementType, Int> =
            mapOf(
                MarkdownElementTypes.ATX_1 to 1,
                MarkdownElementTypes.SETEXT_1 to 1,
                MarkdownElementTypes.ATX_2 to 2,
                MarkdownElementTypes.SETEXT_2 to 2,
                MarkdownElementTypes.ATX_3 to 3,
                MarkdownElementTypes.ATX_4 to 4,
                MarkdownElementTypes.ATX_5 to 5,
                MarkdownElementTypes.ATX_6 to 6,
            )
        val INLINE_STYLES: Map<IElementType, (TextStyles, SpannableStringBuilder, Int, Int) -> Unit> =
            mapOf(
                MarkdownElementTypes.STRONG to { st, s, a, b -> st.bold(s, a, b) },
                MarkdownElementTypes.EMPH to { st, s, a, b -> st.italic(s, a, b) },
                MarkdownElementTypes.CODE_SPAN to { st, s, a, b -> st.mono(s, a, b) },
            )
        val LINK_TYPES: Set<IElementType> =
            setOf(MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK)
        const val PAGE_MARGIN_V = 48
        const val QUOTE_INDENT = 40
        const val LIST_INDENT = 36
        const val LIST_HANG = 28
        const val CODE_SCALE = 0.85f
        const val TABLE_SCALE = 0.8f
    }
}

private fun trim(s: SpannableStringBuilder) {
    while (s.isNotEmpty() && s.last().isWhitespace()) s.delete(s.length - 1, s.length)
    while (s.isNotEmpty() && s.first().isWhitespace()) s.delete(0, 1)
}

private fun lineStartOffsets(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts += 0
    text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
    return starts.toIntArray()
}

/** End offset (after the closing line) of a leading `---` front matter block, or 0 when there is none. */
private fun frontMatterEnd(text: String): Int {
    if (!text.startsWith("---\n")) return 0
    val close = text.indexOf("\n---", 3)
    if (close < 0) return 0
    val lineEnd = text.indexOf('\n', close + 4)
    val rest = if (lineEnd < 0) text.length else lineEnd + 1
    val after = text.substring(close + 4, if (lineEnd < 0) text.length else lineEnd)
    return if (after.isBlank()) rest else 0
}

private fun blank(
    text: String,
    end: Int,
): String {
    val head = text.substring(0, end).map { if (it == '\n') '\n' else ' ' }.joinToString("")
    return head + text.substring(end)
}
