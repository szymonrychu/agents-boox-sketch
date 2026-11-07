package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.model.notebook.ImageRegions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The region images specs reference as `images/<region_id>.png` (Q10 B), fetched from the connector's images route and
 * kept at `<root>/<conversation server id>/<region id>.png` for the conversation's lifetime (a region image never
 * changes; the conversation's delete removes them). Offline or for an unknown image [get] is null and nothing is kept.
 */
class SpecImageCache(
    private val root: File,
    private val client: suspend () -> ConnectorClient?,
) {
    /** The image bytes, from the cache or fetched (and kept); null when unavailable. */
    suspend fun get(
        conversationServerId: String,
        regionId: String,
    ): ByteArray? {
        val file = fileOf(conversationServerId, regionId) ?: return null
        return withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.readBytes() }
            ?: fetch(conversationServerId, regionId)?.takeIf(::isPng)?.also { bytes ->
                withContext(Dispatchers.IO) {
                    val dir = checkNotNull(file.parentFile).also { it.mkdirs() }
                    // A unique temp name: two concurrent gets of one image never write the same file.
                    val part = File.createTempFile("${file.name}.", ".part", dir)
                    part.writeBytes(bytes)
                    if (!part.renameTo(file)) part.delete()
                }
            }
    }

    /** Fetches every image [markdown] references, concurrently; the ones that are not available stay missing. */
    suspend fun prefetch(
        conversationServerId: String,
        markdown: String,
    ) {
        coroutineScope { ids(markdown).map { async { get(conversationServerId, it) } }.awaitAll() }
    }

    @Suppress("SwallowedException") // an image that cannot be fetched now is shown as text and fetched next time
    private suspend fun fetch(
        conversationServerId: String,
        regionId: String,
    ): ByteArray? =
        try {
            client()?.specImage(conversationServerId, regionId)
        } catch (e: ApiError) {
            null
        } catch (e: IOException) {
            null
        }

    /** The kept file of an image, or null when it was not fetched yet. */
    fun cached(
        conversationServerId: String,
        regionId: String,
    ): File? = fileOf(conversationServerId, regionId)?.takeIf { it.isFile }

    /** Removes every image of a conversation. */
    fun delete(conversationServerId: String) {
        if (SAFE_ID.matches(conversationServerId)) File(root, conversationServerId).deleteRecursively()
    }

    /** Removes every image of every conversation (Unpair). */
    fun deleteAll() {
        root.deleteRecursively()
    }

    private fun fileOf(
        conversationServerId: String,
        regionId: String,
    ): File? =
        if (SAFE_ID.matches(conversationServerId) && SAFE_ID.matches(regionId)) {
            File(File(root, conversationServerId), "$regionId.png")
        } else {
            null
        }

    /** A body that is not a PNG (truncated, an error page) is never kept: it would stay "cached" forever. */
    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        /** Ids as they appear in paths: letters, digits and hyphens (uuids). */
        private val SAFE_ID = Regex("[A-Za-z0-9-]+")

        /** The region ids [markdown] references as images, in order, each once. */
        fun ids(markdown: String): List<String> = ImageRegions.referencedIds(markdown)
    }
}
