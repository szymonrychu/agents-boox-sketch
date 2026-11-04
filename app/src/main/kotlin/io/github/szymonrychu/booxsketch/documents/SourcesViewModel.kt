package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.data.AppDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Caption prefix of a notes upload; the thread item text starting with it marks a NOTES source. */
const val ANNOTATION_CAPTION_PREFIX = "Handwritten notes over spec v"

enum class SourceKind { DRAWING, NOTES }

enum class SourceState { LOCAL, REMOTE_PAGE0, GONE }

data class SourceItem(
    val sketchId: String,
    val kind: SourceKind,
    val thumbs: List<File>,
    val state: SourceState,
)

/**
 * The sketches a spec version was made from (`SpecEntity.sketchIds`): the local pages of the matching thread item, else
 * page 0 fetched from the connector and cached; a purged or unknown file is GONE ("no longer stored"), never a crash.
 */
class SourcesViewModel(
    private val serverId: String,
    private val version: Int,
    private val db: AppDatabase,
    private val client: suspend () -> ConnectorClient?,
    private val cacheDir: File,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _items = MutableStateFlow<List<SourceItem>?>(null)

    /** Null while loading. */
    val items: StateFlow<List<SourceItem>?> = _items.asStateFlow()

    init {
        scope.launch { _items.value = load() }
    }

    private suspend fun load(): List<SourceItem> {
        val spec = db.specDao().get(serverId, version) ?: return emptyList()
        val ids = runCatching { Json.decodeFromString(ListSerializer(String.serializer()), spec.sketchIds) }.getOrDefault(emptyList())
        return ids.map { item(it) }
    }

    private suspend fun item(sketchId: String): SourceItem {
        val thread = db.threadDao().byServerId(sketchId)
        val kind = if (thread?.text?.startsWith(ANNOTATION_CAPTION_PREFIX) == true) SourceKind.NOTES else SourceKind.DRAWING
        val local = withContext(io) { localThumbs(thread?.thumbDir) }
        if (local.isNotEmpty()) return SourceItem(sketchId, kind, local, SourceState.LOCAL)
        val remote = withContext(io) { remotePage0(sketchId) }
        return if (remote != null) {
            SourceItem(sketchId, kind, listOf(remote), SourceState.REMOTE_PAGE0)
        } else {
            SourceItem(sketchId, kind, emptyList(), SourceState.GONE)
        }
    }

    private fun localThumbs(dir: String?): List<File> {
        val files = dir?.let { File(it).listFiles() }.orEmpty()
        return files.filter { Regex("sketch\\.\\d+\\.png").matches(it.name) }.sortedBy { it.name.filter(Char::isDigit).toInt() }
    }

    @Suppress("SwallowedException") // purged, missing or offline all read as "no longer stored" here
    private suspend fun remotePage0(sketchId: String): File? {
        val cached = File(cacheDir, "$sketchId.png")
        if (cached.isFile) return cached
        val c = client() ?: return null
        return try {
            val bytes = c.attachment(sketchId)
            cacheDir.mkdirs()
            cached.writeBytes(bytes)
            cached
        } catch (e: ApiError) {
            null
        }
    }
}
