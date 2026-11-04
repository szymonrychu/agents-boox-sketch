package io.github.szymonrychu.booxsketch.send

import androidx.room.withTransaction
import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.DrainResult
import io.github.szymonrychu.booxsketch.api.OutboxItem
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxRunnerCore
import io.github.szymonrychu.booxsketch.api.SketchMetaDto
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.RoomOutboxStorage
import io.github.szymonrychu.booxsketch.documents.AnnotationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What one [OutboxRunner.drain] did. */
data class DrainSummary(
    val accepted: Int,
    val failed: Int,
    val needsPairing: Boolean,
)

/**
 * The app's outbox driver around [OutboxRunnerCore]: single-flight (one upload at a time, across
 * the worker and foreground drains), FIFO per conversation, conversation resolution at first
 * send, and thread status kept in step. [schedule] is asked to run again after the given delay
 * when items are left for later (backoff, Retry-After).
 */
class OutboxRunner(
    private val db: AppDatabase,
    private val client: suspend () -> ConnectorClient?,
    private val onNeedsPairing: suspend () -> Unit,
    private val schedule: (delayMs: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A permanently failed notes upload returns its document draft to editable (see [releaseDraft]). */
    private val annotations: AnnotationStore? = null,
) {
    private val mutex = Mutex()
    private val core =
        OutboxRunnerCore(
            storage = RoomOutboxStorage(db.outboxDao(), db.cursorDao(), conversationOrder = true),
            send = ::send,
            clock = clock,
            resolve = ::resolve,
        )

    /** Sends every due item, one at a time, until none is due or the token is refused. */
    suspend fun drain(): DrainSummary =
        mutex.withLock {
            var accepted = 0
            var failed = 0
            while (true) {
                when (val r = core.drainOnce()) {
                    DrainResult.Idle -> {
                        break
                    }

                    is DrainResult.Accepted -> {
                        accepted++
                        db.threadDao().setStatus(r.item.clientId, ThreadStatus.SENT)
                    }

                    is DrainResult.Failed -> {
                        failed++
                        db.threadDao().setStatus(r.item.clientId, ThreadStatus.failed(r.item.lastError))
                        releaseDraft(r.item)
                    }

                    is DrainResult.WillRetry -> {
                        Unit
                    }

                    DrainResult.NeedsPairing -> {
                        db.threadDao().setStatusForPausedOutbox(ThreadStatus.PAUSED)
                        onNeedsPairing()
                        return@withLock DrainSummary(accepted, failed, needsPairing = true)
                    }
                }
            }
            db.outboxDao().earliestDueMs()?.let { withContext(Dispatchers.IO) { schedule((it - clock()).coerceAtLeast(0)) } }
            DrainSummary(accepted, failed, needsPairing = false)
        }

    /**
     * The draft was marked sent when its uploads were queued; once one fails for good it must be editable again.
     * An unreadable payload cannot name its version, so the draft then stays as it is.
     */
    private suspend fun releaseDraft(item: OutboxItem) {
        val store = annotations ?: return
        if (item.kind != OutboxKind.SKETCH) return
        val version =
            withContext(Dispatchers.IO) { runCatching { Payload.readPages(File(item.payloadDir)).annotates?.specVersion }.getOrNull() }
        if (version != null) store.unmarkSent(item.conversationLocalId, version)
    }

    /** After re-pairing: paused items become pending again; the caller then kicks a drain. */
    suspend fun resumeAfterPairing() {
        core.resumeAfterPairing()
        db.threadDao().replaceStatus(ThreadStatus.PAUSED, ThreadStatus.QUEUED)
    }

    /**
     * Runs once per item, when it is first sent (the runner is single-flight): uses the
     * conversation's server id when known, else `conversation = "new"` (sketches) or a created
     * conversation (messages, hand-offs). Writes the final `meta.json`; the returned fields are
     * persisted on the item and reused by every retry.
     */
    private suspend fun resolve(item: OutboxItem): OutboxItem =
        payload {
            val conversation =
                db.conversationDao().get(item.conversationLocalId)
                    ?: throw ApiError.NotFound("conversation_deleted")
            val dir = File(item.payloadDir)
            when (item.kind) {
                OutboxKind.SKETCH -> {
                    val serverId = conversation.serverId
                    val pages = Payload.readPages(dir)
                    val meta =
                        SketchMetaDto(
                            clientId = item.clientId,
                            conversation = if (serverId == null) MODE_NEW else MODE_CURRENT,
                            conversationId = serverId,
                            caption = pages.caption,
                            pages = pages.pages,
                            annotates = pages.annotates,
                        )
                    withContext(Dispatchers.IO) { Payload.writeMeta(dir, meta) }
                    item.copy(conversationServerId = serverId, conversationMode = meta.conversation)
                }

                OutboxKind.MESSAGE, OutboxKind.HANDOFF -> {
                    val serverId =
                        conversation.serverId ?: connector().createConversation().also {
                            db.conversationDao().setServerId(conversation.localId, it)
                        }
                    item.copy(conversationServerId = serverId, conversationMode = MODE_CURRENT)
                }
            }
        }

    private suspend fun send(item: OutboxItem): String =
        payload {
            val c = connector()
            val dir = File(item.payloadDir)
            when (item.kind) {
                OutboxKind.SKETCH -> {
                    val (meta, pngs, strokes) =
                        withContext(Dispatchers.IO) {
                            val meta = Payload.readMeta(dir)
                            val n = meta.pages.indices
                            Triple(
                                meta,
                                n.map { Payload.png(dir, it).readBytes() },
                                n.map { Payload.strokes(dir, it).takeIf(File::isFile)?.readBytes() },
                            )
                        }
                    // 202 and a 200 replay of the same client_id both count as accepted.
                    val result = c.uploadSketch(meta, pngs, strokes)
                    // Stored before the next item is resolved, so a later send in this new conversation reuses it.
                    // One transaction: a refresh that saw the new server conversation first left a twin row, drop it.
                    db.withTransaction {
                        db.threadDao().setServerId(item.clientId, result.sketchId)
                        val row = db.conversationDao().get(item.conversationLocalId)
                        if (row != null && row.serverId == null) {
                            db.conversationDao().deleteOthersWithServerId(result.conversationId, row.localId)
                            db.conversationDao().setServerId(row.localId, result.conversationId)
                        }
                    }
                    result.sketchId
                }

                OutboxKind.MESSAGE -> {
                    val message = withContext(Dispatchers.IO) { Payload.readMessage(dir) }
                    c.postMessage(checkNotNull(item.conversationServerId), item.clientId, message.text)
                }

                OutboxKind.HANDOFF -> {
                    val handoff = withContext(Dispatchers.IO) { Payload.readHandoff(dir) }
                    c.handoff(checkNotNull(item.conversationServerId), handoff.req)
                    item.clientId
                }
            }
        }

    private suspend fun connector(): ConnectorClient = client() ?: throw ApiError.Unauthorized

    /** A missing or unreadable payload can never succeed: it fails the item instead of crashing the drain. */
    @Suppress("SwallowedException") // the reason travels in the item's lastError; ApiError carries no cause
    private suspend fun <T> payload(block: suspend () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw ApiError.Invalid("payload_unreadable", e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            // Also kotlinx SerializationException.
            throw ApiError.Invalid("payload_unreadable", e.message.orEmpty())
        } catch (e: NoSuchElementException) {
            throw ApiError.Invalid("payload_unreadable", e.message.orEmpty())
        }

    private companion object {
        const val MODE_NEW = "new"
        const val MODE_CURRENT = "current"
    }
}
