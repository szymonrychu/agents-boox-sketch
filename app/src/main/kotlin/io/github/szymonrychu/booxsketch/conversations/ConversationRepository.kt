package io.github.szymonrychu.booxsketch.conversations

import android.content.SharedPreferences
import androidx.room.withTransaction
import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.ConversationDto
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.documents.AnnotationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** Title of a conversation the server has not named yet. */
const val DEFAULT_CONVERSATION_TITLE = "New conversation"

private const val CAPTION_TITLE_CHARS = 60
private const val KEY_SELECTED = "selected_conversation"

/**
 * Local conversations (Room) merged with the server list, plus the selected conversation that
 * sends go to. The selection lives here (persisted in [prefs]), never in a composable.
 */
class ConversationRepository(
    private val db: AppDatabase,
    private val client: suspend () -> ConnectorClient?,
    private val prefs: SharedPreferences,
    private val outboxRoot: File,
    private val onUnauthorized: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Runs after every successful [refresh], with [latestSpecVersions] and [latestFeedbackIds] already updated (sync hook). */
    private val afterRefresh: suspend () -> Unit = {},
    /** The frozen pages and ink of documents; a conversation that goes away takes its annotations with it. */
    private val annotations: AnnotationStore? = null,
) {
    private val dao = db.conversationDao()
    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null))

    /** Local id of the selected conversation; null means "the most recent one". */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** Latest spec version per server conversation id, from the last successful [refresh]. */
    @Volatile
    var latestSpecVersions: Map<String, Int> = emptyMap()
        private set

    /** Latest feedback id per server conversation id, from the last successful [refresh]. */
    @Volatile
    var latestFeedbackIds: Map<String, Int> = emptyMap()
        private set

    /** Active conversations, most recent first. */
    val conversations: Flow<List<ConversationEntity>> = dao.observeAll()

    fun select(localId: String?) {
        _selected.value = localId
        prefs.edit().putString(KEY_SELECTED, localId).apply()
    }

    /** Creates a local conversation (its server id arrives with the first send) and selects it. */
    suspend fun newConversation(): String {
        val id = newId()
        val now = clock()
        dao.upsert(ConversationEntity(id, null, DEFAULT_CONVERSATION_TITLE, now, now))
        select(id)
        return id
    }

    /** Where a send goes: the selected conversation, else the most recent one, else a new one. */
    suspend fun current(): ConversationEntity {
        _selected.value?.let { id -> dao.get(id)?.takeUnless { it.deleted }?.let { return it } }
        dao.latest()?.let {
            select(it.localId)
            return it
        }
        return checkNotNull(dao.get(newConversation()))
    }

    /** Names a still unnamed conversation after the caption of its first sketch. */
    suspend fun titleFromCaption(
        localId: String,
        caption: String?,
    ) {
        val row = dao.get(localId) ?: return
        if (caption.isNullOrBlank() || row.title != DEFAULT_CONVERSATION_TITLE) return
        dao.setTitle(localId, caption.trim().take(CAPTION_TITLE_CHARS))
    }

    /**
     * Merges `GET /conversations` into Room: unknown server conversations get a local row, known
     * ones take the server title when it has one, and rows the complete list no longer has are
     * dropped. The list is not paged, so a successful call is complete; a failed one returns
     * early and prunes nothing. Returns an error text, or null on success.
     */
    suspend fun refresh(): String? {
        val c = client() ?: return "Not paired"
        // Only rows whose server id was known before the request can be judged by its answer: a
        // conversation acked while the request was in flight may be missing from the list.
        val knownBefore = dao.all().mapNotNull { it.serverId }.toSet()
        val remote =
            try {
                c.conversations()
            } catch (e: ApiError) {
                if (e == ApiError.Unauthorized) onUnauthorized()
                return "Could not load conversations: ${e.message}"
            }
        val pruned = db.withTransaction { merge(remote, knownBefore) }
        pruned.forEach { annotations?.delete(it) }
        if (_selected.value in pruned) select(null)
        latestSpecVersions = remote.mapNotNull { r -> r.latestSpecVersion?.let { r.id to it } }.toMap()
        latestFeedbackIds = remote.mapNotNull { r -> r.latestFeedbackId?.let { r.id to it } }.toMap()
        afterRefresh()
        return null
    }

    /** Inserts and renames from [remote], drops rows the list no longer has; returns the local ids dropped. */
    private suspend fun merge(
        remote: List<ConversationDto>,
        knownBefore: Set<String>,
    ): List<String> {
        val remoteIds = remote.map { it.id }.toSet()
        val known = dao.all().filter { it.serverId != null }.associateBy { it.serverId }
        for (r in remote) {
            val local = known[r.id]
            if (local == null) {
                val ms = parseMs(r.createdAt)
                dao.upsert(ConversationEntity(newId(), r.id, r.title ?: DEFAULT_CONVERSATION_TITLE, ms, ms))
            } else {
                val title = r.title
                if (title != null && title != local.title) dao.setTitle(local.localId, title)
            }
        }
        // Rows with queued sends stay: their items still need the conversation.
        return known.values
            .filter {
                it.serverId in knownBefore && it.serverId !in remoteIds &&
                    db.outboxDao().forConversation(it.localId).isEmpty()
            }.onEach { row ->
                row.serverId?.let {
                    db.specDao().deleteFor(it)
                    db.feedbackDao().deleteFor(it)
                    db.cursorDao().delete(it)
                }
                dao.delete(row.localId)
            }.map { it.localId }
    }

    /**
     * Deletes a conversation: on the server first (online only, a 404 counts as gone), then the
     * local row with its thread, specs, cursor, outbox items and payloads. Returns an error text,
     * or null when deleted.
     */
    suspend fun delete(localId: String): String? {
        val row = dao.get(localId) ?: return null
        row.serverId?.let { sid ->
            val c = client() ?: return "Not paired"
            try {
                c.deleteConversation(sid)
            } catch (_: ApiError.NotFound) {
                // Already gone on the server.
            } catch (e: ApiError) {
                if (e == ApiError.Unauthorized) onUnauthorized()
                return "Could not delete (needs a connection): ${e.message}"
            }
        }
        val dirs =
            db.withTransaction {
                val outbox = db.outboxDao()
                val dirs = outbox.forConversation(localId).map { it.payloadDir }
                outbox.deleteForConversation(localId)
                row.serverId?.let {
                    db.specDao().deleteFor(it)
                    db.feedbackDao().deleteFor(it)
                    db.cursorDao().delete(it)
                }
                // Thread items go with the row (foreign key ON DELETE CASCADE).
                dao.delete(localId)
                dirs
            }
        val root = outboxRoot.canonicalFile
        dirs.map { File(it).canonicalFile }.filter { it.parentFile == root }.forEach { it.deleteRecursively() }
        annotations?.delete(localId)
        if (_selected.value == localId) select(null)
        return null
    }

    private fun parseMs(iso: String): Long =
        try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            clock()
        }
}
