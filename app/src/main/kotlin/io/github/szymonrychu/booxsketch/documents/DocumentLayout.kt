package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import kotlinx.serialization.Serializable

/**
 * The fixed layout of a document: [widthPx] x [pageHeightPx] per page, the same in both orientations (the notebook
 * canvas size in portrait). [fontScale] 1 gives body text of [BODY_TEXT_PX], which is at least 28 px once the page is
 * exported at the connector's long-edge cap (1568 px).
 */
@Serializable
data class DocumentLayout(
    val widthPx: Int,
    val pageHeightPx: Int,
    val fontScale: Float = 1f,
) {
    companion object {
        const val BODY_TEXT_PX = 34f
    }
}

/** A page bitmap with the source line range of the markdown it shows. */
data class RenderedPage(
    val bitmap: Bitmap,
    val sourceLines: IntRange,
    /** True when a diagram on this page fell back to code text; the freeze is then retried on the next open. */
    val diagramFailed: Boolean = false,
)

/** Draws a diagram source (for example mermaid) to a bitmap of [widthPx]; null means "could not", the code is shown as text. */
fun interface BlockImage {
    fun render(
        source: String,
        widthPx: Int,
    ): Bitmap?
}
