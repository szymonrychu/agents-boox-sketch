package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Share .md (Q10 B): a spec that references region images is shared as `spec-v<N>.zip` holding `spec-v<N>.md` (the
 * markdown unchanged, its `images/<id>.png` references resolve inside the zip) and `images/<id>.png` for every
 * referenced image; an image that cannot be had is left out and its id listed in `MISSING.txt`. A spec without images
 * stays the plain text share.
 */
object ShareBundle {
    /** The bundles live here under the cache dir, shared through the FileProvider `<package>.share`. */
    const val DIR = "share"

    fun needsBundle(markdown: String): Boolean = SpecImageCache.ids(markdown).isNotEmpty()

    /** Writes `spec-v<version>.zip` into [dir] (replacing an earlier one) and returns it. */
    suspend fun build(
        dir: File,
        version: Int,
        markdown: String,
        image: suspend (String) -> ByteArray?,
    ): File {
        val images = SpecImageCache.ids(markdown).map { it to image(it) }
        return withContext(Dispatchers.IO) {
            dir.mkdirs()
            val zip = File(dir, "spec-v$version.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.entry("spec-v$version.md", markdown.toByteArray(Charsets.UTF_8))
                images.forEach { (id, bytes) -> bytes?.let { out.entry("images/$id.png", it) } }
                val missing = images.filter { it.second == null }.map { it.first }
                if (missing.isNotEmpty()) {
                    out.entry("MISSING.txt", ("Images not available when shared:\n" + missing.joinToString("\n") + "\n").toByteArray())
                }
            }
            zip
        }
    }

    /** The Share .md intent for spec [version]: the zip through the FileProvider when [markdown] has images, else text. */
    suspend fun intent(
        context: Context,
        version: Int,
        markdown: String,
        image: suspend (String) -> ByteArray?,
    ): Intent {
        if (!needsBundle(markdown)) {
            return Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, markdown)
        }
        val zip = build(File(context.cacheDir, DIR), version, markdown, image)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", zip)
        return Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun ZipOutputStream.entry(
        name: String,
        bytes: ByteArray,
    ) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
