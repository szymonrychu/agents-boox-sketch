package io.github.szymonrychu.booxsketch.conversations

import android.content.SharedPreferences
import androidx.room.withTransaction
import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
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
) {
    private val dao = db.conversationDao()
    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null))

    /** Local id of the selected conversation; null means "the most recent one". */
    val selected: StateFlow<String?> = _selected.asStateFlow()

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
     * ones take the server title when it has one. Returns an error text, or null on success.
     */
    suspend fun refresh(): String? {
        val c = client() ?: return "Not paired"
        val remote =
            try {
                c.conversations()
            } catch (e: ApiError) {
                if (e == ApiError.Unauthorized) onUnauthorized()
                return "Could not load conversations: ${e.message}"
            }
        db.withTransaction {
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
        }
        return null
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
                    db.cursorDao().delete(it)
                }
                // Thread items go with the row (foreign key ON DELETE CASCADE).
                dao.delete(localId)
                dirs
            }
        val root = outboxRoot.canonicalFile
        dirs.map { File(it).canonicalFile }.filter { it.parentFile == root }.forEach { it.deleteRecursively() }
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
