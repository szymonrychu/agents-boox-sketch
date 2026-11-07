package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MarkdownPaginatorTest {
    private val layout = DocumentLayout(widthPx = 1404, pageHeightPx = 1872)

    private fun paginator(
        l: DocumentLayout = layout,
        mermaid: BlockImage? = null,
    ) = MarkdownPaginator(l, mermaid)

    private fun inked(bitmap: Bitmap): Boolean {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return px.any { it != Color.WHITE }
    }

    private val sample =
        """
        # Title

        A paragraph with **bold**, *italic*, `code` and a [link](https://example.invalid).

        - one
        - two
          - nested

        1. first
        2. second

        > quoted text

        ```kotlin
        val x = 1
        ```

        | a | b |
        |---|---|
        | 1 | 2 |

        ---
        """.trimIndent()

    @Test
    fun empty_markdown_gives_one_blank_page() {
        val pages = paginator().paginate("")
        assertThat(pages).hasSize(1)
        assertThat(inked(pages[0].bitmap)).isFalse()
    }

    @Test
    fun heading_paragraph_list_fit_one_page_with_line_ranges() {
        val pages = paginator().paginate(sample)
        assertThat(pages).hasSize(1)
        assertThat(inked(pages[0].bitmap)).isTrue()
        assertThat(pages[0].sourceLines.first).isEqualTo(0)
        assertThat(pages[0].sourceLines.last).isEqualTo(sample.lines().lastIndex)
    }

    @Test
    fun long_document_paginates_and_every_source_line_is_covered_once() {
        val lines = (0 until 120).map { "Paragraph number $it with some words to fill the line." }
        val md = lines.joinToString("\n\n")
        val pages = paginator().paginate(md)
        assertThat(pages.size).isGreaterThan(1)
        val covered = pages.flatMap { it.sourceLines.toList() }.toSet()
        val nonBlank = md.lines().indices.filter { md.lines()[it].isNotBlank() }
        assertThat(covered).containsAtLeastElementsIn(nonBlank)
        // Paragraphs are whole blocks: pages are in order and never go back.
        assertThat(pages.map { it.sourceLines.first }).isInOrder()
    }

    @Test
    fun wide_table_does_not_exceed_width() {
        val cells = (1..20).joinToString(" | ") { "column$it" }
        val md = "| $cells |\n|${"---|".repeat(20)}\n| ${(1..20).joinToString(" | ") { "value$it" }} |"
        val pages = paginator().paginate(md)
        assertThat(pages.all { it.bitmap.width == layout.widthPx }).isTrue()
        assertThat(inked(pages[0].bitmap)).isTrue()
    }

    @Test
    fun page_bitmaps_have_layout_size() {
        val pages = paginator().paginate(sample)
        assertThat(pages.all { it.bitmap.width == 1404 && it.bitmap.height == 1872 }).isTrue()
    }

    @Test
    fun same_input_same_bitmaps() {
        val a = paginator().paginate(sample)
        val b = paginator().paginate(sample)
        assertThat(a).hasSize(b.size)
        a.zip(b).forEach { (x, y) -> assertThat(x.bitmap.sameAs(y.bitmap)).isTrue() }
    }

    @Test
    fun layout_independent_of_orientation() {
        val portrait = paginator().paginate(sample)
        RuntimeEnvironment.setQualifiers("+land")
        val landscape = paginator().paginate(sample)
        assertThat(landscape).hasSize(portrait.size)
        portrait.zip(landscape).forEach { (x, y) ->
            assertThat(x.bitmap.sameAs(y.bitmap)).isTrue()
            assertThat(x.sourceLines).isEqualTo(y.sourceLines)
        }
    }

    @Test
    fun mermaid_block_uses_hook_when_present_else_code_text() {
        val md = "```mermaid\ngraph TD; A-->B\n```"
        var seen: String? = null
        val image = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val withHook = paginator(mermaid = { source, _ -> image.also { seen = source } }).paginate(md)
        assertThat(seen).isEqualTo("graph TD; A-->B")
        assertThat(withHook[0].bitmap.getPixel(TextStyles.MARGIN_PX + 10, 48 + 10)).isEqualTo(Color.RED)
        val fallback = paginator(mermaid = { _, _ -> null }).paginate(md)
        assertThat(fallback[0].bitmap.getPixel(TextStyles.MARGIN_PX + 10, 48 + 10)).isNotEqualTo(Color.RED)
        assertThat(inked(fallback[0].bitmap)).isTrue()
    }

    @Test
    fun failed_diagram_shows_code_then_a_one_line_reason_caption() {
        val md = "```mermaid\ngraph TD; A-->\n```"
        val renderer =
            object : DiagramRenderer {
                override fun renderDiagram(
                    source: String,
                    widthPx: Int,
                ) = DiagramResult.Failed(DiagramFailure.Parse("Parse error on line 1"))
            }
        val p = paginator(mermaid = renderer)
        val text = p.textBlocks(md).single()
        assertThat(text).isEqualTo("graph TD; A-->\ndiagram failed: mermaid parse error: Parse error on line 1")
        assertThat(p.paginate(md)[0].diagramFailed).isTrue()
    }

    @Test
    fun diagrams_after_the_document_budget_are_skipped_with_a_reason() {
        var calls = 0
        val renderer =
            object : DiagramRenderer {
                override fun renderDiagram(
                    source: String,
                    widthPx: Int,
                ): DiagramResult {
                    calls++
                    return DiagramResult.Failed(DiagramFailure.Timeout(20_000))
                }
            }
        val md = "```mermaid\na\n```\n\n```mermaid\nb\n```"
        val texts = MarkdownPaginator(DocumentLayout(600, 800), renderer, diagramBudgetMs = 0).textBlocks(md)
        assertThat(calls).isEqualTo(0)
        assertThat(texts).hasSize(2)
        assertThat(texts[1]).endsWith("diagram failed: skipped, document diagram time used up")
    }

    @Test
    fun plain_hook_returning_null_is_captioned_too() {
        val p = paginator(mermaid = { _, _ -> null })
        assertThat(p.textBlocks("```mermaid\nx\n```").single()).endsWith("diagram failed: renderer returned no image")
    }

    @Test
    fun successful_diagram_and_non_mermaid_fence_have_no_caption_or_failed_flag() {
        val image = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val ok = paginator(mermaid = { _, _ -> image }).paginate("```mermaid\nx\n```")
        assertThat(ok[0].diagramFailed).isFalse()
        val code = paginator(mermaid = { _, _ -> null })
        assertThat(code.textBlocks("```kotlin\nval a = 1\n```").single()).isEqualTo("val a = 1")
        assertThat(code.paginate("```kotlin\nval a = 1\n```")[0].diagramFailed).isFalse()
    }

    @Test
    fun front_matter_is_shown_as_code_and_keeps_line_numbers() {
        val md = "---\ntitle: x\n---\n\n# Body"
        val pages = paginator().paginate(md)
        assertThat(pages).hasSize(1)
        assertThat(pages[0].sourceLines).isEqualTo(0..4)
    }

    @Test
    fun oversize_single_block_never_crashes() {
        val md = (1..400).joinToString(" ") { "word$it" }
        val pages = paginator(DocumentLayout(1404, 300)).paginate(md)
        assertThat(pages.size).isAtLeast(2)
    }

    @Test
    fun colour_markers_become_a_readable_label_in_the_paragraph() {
        assertThat(paginator().textBlocks("[[red]] Login button")).containsExactly("[red] Login button")
    }

    @Test
    fun colour_markers_work_in_lists_headings_and_quotes() {
        val texts = paginator().textBlocks("# [[blue]] Title\n\n- [[green]] one\n- two\n\n> [[orange]] said")
        assertThat(texts).hasSize(3)
        assertThat(texts[0]).isEqualTo("[blue] Title")
        assertThat(texts[1]).isEqualTo("- [green] one\n- two")
        assertThat(texts[2]).isEqualTo("[orange] said")
    }

    @Test
    fun uncertain_markers_and_code_stay_as_written() {
        val texts = paginator().textBlocks("Read [[?ward|word]] or [[illegible]] here\n\n```\n[[red]] literal\n```")
        assertThat(texts).containsExactly("Read [[?ward|word]] or [[illegible]] here", "[[red]] literal").inOrder()
    }

    @Test
    fun a_colour_marker_is_drawn_in_its_colour() {
        val page = paginator().paginate("[[red]] Login button").single()
        val px = IntArray(page.bitmap.width * page.bitmap.height)
        page.bitmap.getPixels(px, 0, page.bitmap.width, 0, 0, page.bitmap.width, page.bitmap.height)
        assertThat(px.any { Color.red(it) > 150 && Color.green(it) < 90 && Color.blue(it) < 90 }).isTrue()
        val plain = paginator().paginate("Login button").single()
        val plainPx = IntArray(plain.bitmap.width * plain.bitmap.height)
        plain.bitmap.getPixels(plainPx, 0, plain.bitmap.width, 0, 0, plain.bitmap.width, plain.bitmap.height)
        assertThat(plainPx.none { Color.red(it) > 150 && Color.green(it) < 90 && Color.blue(it) < 90 }).isTrue()
    }

    @Test
    fun image_reference_renders_image_block() {
        val red = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val p = MarkdownPaginator(layout, images = { id -> red.takeIf { id == "abc-1" } })
        val md = "Before\n\n![image](images/abc-1.png)\n\nAfter"
        assertThat(p.textBlocks(md)).containsExactly("Before", "After").inOrder()
        val page = p.paginate(md).single()
        // Scaled to the column width, aspect kept: about textWidth x textWidth / 2 of red.
        val px = IntArray(page.bitmap.width * page.bitmap.height)
        page.bitmap.getPixels(px, 0, page.bitmap.width, 0, 0, page.bitmap.width, page.bitmap.height)
        val width = TextStyles(layout).textWidth
        assertThat(px.count { it == Color.RED }).isAtLeast(width * width / 2 * 9 / 10)
        // The image keeps its source line for annotations.
        assertThat(page.sourceLines.first).isAtMost(2)
        assertThat(page.sourceLines.last).isAtLeast(4)
    }

    @Test
    fun unknown_image_falls_back_to_text() {
        val p = MarkdownPaginator(layout, images = { null })
        assertThat(p.textBlocks("![image](images/abc-1.png)")).containsExactly("[image]")
        assertThat(paginator().textBlocks("![image](images/abc-1.png)")).containsExactly("[image]")
    }
}
