package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.DocumentRaw
import io.github.szymonrychu.booxsketch.data.ThreadItemEntity
import io.github.szymonrychu.booxsketch.send.ThreadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

enum class DocState { IDLE, TRANSLATING, FAILED }

data class DocumentRow(
    val conversationLocalId: String,
    val serverId: String,
    val title: String,
    val latestVersion: Int,
    val latestCreated: String,
    val state: DocState,
)

/** One page of the Documents list; [loaded] is false until the first query answered. */
data class DocumentsPage(
    val rows: List<DocumentRow> = emptyList(),
    val index: Int = 0,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * The Documents list: conversations with at least one spec version, newest activity first, ten per page. A row is
 * TRANSLATING while a sketch of the conversation is queued or paused, or was sent after the latest spec appeared, and
 * FAILED when the newest sketch failed.
 */
class DocumentsViewModel(
    db: AppDatabase,
    scope: CoroutineScope,
    private val onOpen: (DocumentRow) -> Unit = {},
) {
    private val index = MutableStateFlow(0)

    val page: StateFlow<DocumentsPage> =
        combine(db.documentDao().observeDocuments(), db.documentDao().observeSketches(), index) { docs, sketches, requested ->
            val rows = docs.map { it.toRow(sketches.filter { s -> s.conversationLocalId == it.conversationLocalId }) }
            val last = ((rows.size - 1) / PAGE_SIZE).coerceAtLeast(0)
            val i = requested.coerceIn(0, last)
            DocumentsPage(
                rows = rows.drop(i * PAGE_SIZE).take(PAGE_SIZE),
                index = i,
                hasPrev = i > 0,
                hasNext = i < last,
                loaded = true,
            )
        }.stateIn(scope, SharingStarted.Eagerly, DocumentsPage())

    fun next() {
        if (page.value.hasNext) index.value = page.value.index + 1
    }

    fun prev() {
        if (page.value.hasPrev) index.value = page.value.index - 1
    }

    fun open(row: DocumentRow) = onOpen(row)

    private fun DocumentRaw.toRow(sketches: List<ThreadItemEntity>) =
        DocumentRow(conversationLocalId, serverId, title, latestVersion, latestCreated, stateOf(sketches, latestCreated))

    companion object {
        const val PAGE_SIZE = 10

        internal fun stateOf(
            sketches: List<ThreadItemEntity>,
            latestCreated: String,
        ): DocState {
            val newest = sketches.maxByOrNull { it.createdAtMs } ?: return DocState.IDLE
            return when {
                newest.status.startsWith("failed") -> DocState.FAILED
                newest.status == ThreadStatus.QUEUED || newest.status == ThreadStatus.PAUSED -> DocState.TRANSLATING
                newest.status == ThreadStatus.SENT && newest.createdAtMs > parseMs(latestCreated) -> DocState.TRANSLATING
                else -> DocState.IDLE
            }
        }

        private fun parseMs(iso: String): Long =
            try {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                0L
            }
    }
}
