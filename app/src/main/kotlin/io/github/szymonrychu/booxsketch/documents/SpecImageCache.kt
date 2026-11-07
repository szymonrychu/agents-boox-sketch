package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import kotlinx.coroutines.Dispatchers
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
            ?: fetch(conversationServerId, regionId)?.also { bytes ->
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    val part = File(file.parentFile, "${file.name}.part")
                    part.writeBytes(bytes)
                    part.renameTo(file)
                }
            }
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

    private fun fileOf(
        conversationServerId: String,
        regionId: String,
    ): File? =
        if (SAFE_ID.matches(conversationServerId) && SAFE_ID.matches(regionId)) {
            File(File(root, conversationServerId), "$regionId.png")
        } else {
            null
        }

    companion object {
        /** Ids as they appear in paths: letters, digits and hyphens (uuids). */
        private val SAFE_ID = Regex("[A-Za-z0-9-]+")

        private val REFERENCE = Regex("""!\[[^\]]*]\(images/([A-Za-z0-9-]+)\.png\)""")

        /** The region ids [markdown] references as images, in order, each once. */
        fun ids(markdown: String): List<String> =
            REFERENCE
                .findAll(markdown)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
    }
}
