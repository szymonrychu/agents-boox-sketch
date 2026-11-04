package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import io.github.szymonrychu.booxsketch.board.NotebookStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class FrozenPage(
    val file: File,
    val sourceLines: IntRange,
)

data class FrozenDocument(
    val layout: DocumentLayout,
    val pages: List<FrozenPage>,
)

/**
 * The frozen page bitmaps of a document version and the ink drawn over them, under
 * `<root>/annotations/<conversation local id>/v<version>/`. The bitmap the user inked on is the bitmap that is exported:
 * freezing is idempotent and an existing complete freeze is never overwritten, so old ink cannot move.
 */
class AnnotationStore(
    root: File,
) {
    private val base = File(root, "annotations")
    private val json = Json { ignoreUnknownKeys = true }

    fun dir(
        conversationLocalId: String,
        version: Int,
    ): File = File(File(base, conversationLocalId), "v$version")

    /** Writes the page PNGs, then `layout.json` (the completeness marker); returns the existing freeze when there is one. */
    @Synchronized
    fun freeze(
        conversationLocalId: String,
        version: Int,
        pages: List<RenderedPage>,
        layout: DocumentLayout,
    ): FrozenDocument {
        frozen(conversationLocalId, version)?.let { return it }
        val dir = dir(conversationLocalId, version).apply { mkdirs() }
        File(dir, LAYOUT_FILE).delete()
        val entries =
            pages.mapIndexed { i, page ->
                val name = "page.$i.png"
                val tmp = File(dir, "$name.tmp")
                tmp.outputStream().use { check(page.bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) { "cannot write $name" } }
                replace(tmp, File(dir, name))
                PageEntry(name, page.sourceLines.first, page.sourceLines.last)
            }
        val tmp = File(dir, "$LAYOUT_FILE.tmp")
        tmp.writeText(json.encodeToString(FrozenFile.serializer(), FrozenFile(layout, entries)))
        replace(tmp, File(dir, LAYOUT_FILE))
        return checkNotNull(frozen(conversationLocalId, version)) { "freeze not readable" }
    }

    /** The frozen document, or null when absent, incomplete or unreadable. */
    @Synchronized
    fun frozen(
        conversationLocalId: String,
        version: Int,
    ): FrozenDocument? {
        val dir = dir(conversationLocalId, version)
        val file = File(dir, LAYOUT_FILE)
        if (!file.isFile) return null
        val parsed = runCatching { json.decodeFromString(FrozenFile.serializer(), file.readText()) }.getOrNull() ?: return null
        val pages = parsed.pages.map { FrozenPage(File(dir, it.file), it.first..it.last) }
        return if (pages.all { it.file.isFile }) FrozenDocument(parsed.layout, pages) else null
    }

    /** The ink of one document version: one notebook file per (conversation, version). */
    fun notebookStore(
        conversationLocalId: String,
        version: Int,
    ): NotebookStore = NotebookStore(File(dir(conversationLocalId, version), NOTEBOOK_FILE))

    fun markSent(
        conversationLocalId: String,
        version: Int,
    ) {
        val dir = dir(conversationLocalId, version).apply { mkdirs() }
        File(dir, SENT_FILE).writeText("sent")
    }

    /** A notes upload failed for good: the draft is editable and can be sent again. */
    fun unmarkSent(
        conversationLocalId: String,
        version: Int,
    ) {
        File(dir(conversationLocalId, version), SENT_FILE).delete()
    }

    fun isSent(
        conversationLocalId: String,
        version: Int,
    ): Boolean = File(dir(conversationLocalId, version), SENT_FILE).isFile

    /** Removes every version of a conversation. */
    fun delete(conversationLocalId: String) {
        File(base, conversationLocalId).deleteRecursively()
    }

    /** Removes every annotation (unpair). */
    fun deleteAll() {
        base.deleteRecursively()
    }

    private fun replace(
        tmp: File,
        target: File,
    ) {
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot replace ${target.name}" }
        }
    }

    @Serializable
    private data class PageEntry(
        val file: String,
        val first: Int,
        val last: Int,
    )

    @Serializable
    private data class FrozenFile(
        val layout: DocumentLayout,
        val pages: List<PageEntry>,
    )

    private companion object {
        const val LAYOUT_FILE = "layout.json"
        const val NOTEBOOK_FILE = "notebook.json"
        const val SENT_FILE = "sent"
        const val PNG_QUALITY = 100
    }
}
