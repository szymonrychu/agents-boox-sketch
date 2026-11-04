package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.api.AnnotatedPageDto
import io.github.szymonrychu.booxsketch.api.AnnotatesDto
import io.github.szymonrychu.booxsketch.model.Page
import java.io.File

/** One document page to send: [index] is its page in the document notebook, [background] the frozen page bitmap. */
data class AnnotatedInput(
    val index: Int,
    val background: File,
    val page: Page,
    val docPage: Int,
    val lines: IntRange,
)

/** Pure helpers of a notes send: caption, upload plan and `meta.annotates`. */
object AnnotationSend {
    /** The caption of a notes upload: "Handwritten notes over spec v3", the user's text appended after a space. */
    fun caption(
        version: Int,
        userCaption: String?,
    ): String =
        ANNOTATION_CAPTION_PREFIX + version +
            userCaption
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { " $it" }
                .orEmpty()

    /** Only the pages with ink, in page order, in uploads of at most [maxPages] pages each. */
    fun plan(
        pages: List<AnnotatedInput>,
        maxPages: Int,
    ): List<List<AnnotatedInput>> =
        pages
            .filter { it.page.strokes.isNotEmpty() }
            .sortedBy { it.index }
            .chunked(maxPages.coerceAtLeast(1))

    /** `meta.annotates` of one upload; `page` is the page index also used in `meta.pages`. */
    fun annotates(
        version: Int,
        upload: List<AnnotatedInput>,
    ): AnnotatesDto =
        AnnotatesDto(
            version,
            upload.map {
                AnnotatedPageDto(
                    it.index,
                    it.docPage,
                    if (it.lines.isEmpty()) listOf(0, 0) else listOf(it.lines.first, it.lines.last),
                )
            },
        )
}
