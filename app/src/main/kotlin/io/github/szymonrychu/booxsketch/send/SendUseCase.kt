package io.github.szymonrychu.booxsketch.send

import androidx.room.withTransaction
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.HandoffDto
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.OutboxEntity
import io.github.szymonrychu.booxsketch.data.ThreadItemEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.SendSelection
import io.github.szymonrychu.booxsketch.model.StrokesCodec
import io.github.szymonrychu.booxsketch.model.selectPagesForSend
import io.github.szymonrychu.booxsketch.render.ExportResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

sealed interface SendOutcome {
    data class Queued(
        val clientId: String,
    ) : SendOutcome

    data object NothingToSend : SendOutcome

    data class TooManyPages(
        val count: Int,
        val max: Int,
    ) : SendOutcome

    data class TooLarge(
        val bytes: Long,
        val max: Long,
    ) : SendOutcome

    data class ExportFailed(
        val page: Int,
        val reason: String,
    ) : SendOutcome
}

/** What a send needs from the board: the notebook and marking pages sent. */
interface SendBoard {
    fun notebook(): Notebook

    /** Marks the sent pages, unless they changed since [sent] was taken. */
    suspend fun markSent(sent: Map<Int, Page>)
}

/** Status of a thread item, kept in step with its outbox item. */
object ThreadStatus {
    const val QUEUED = "queued"
    const val SENT = "sent"
    const val PAUSED = "paused"

    fun failed(reason: String?) = "failed: ${reason ?: "unknown"}"
}

/**
 * Turns board pages, feedback text and hand-offs into outbox items. Every check (page count,
 * export limits, total bytes) runs before anything is written; then the payload directory
 * `outbox/<clientId>/` is written and the outbox row plus its thread item land in one Room
 * transaction. The conversation is resolved later, when the item is first sent ([OutboxRunner]).
 */
@Suppress("LongParameterList")
class SendUseCase(
    private val db: AppDatabase,
    private val conversations: ConversationRepository,
    private val board: SendBoard,
    private val export: (Page, Canvas, ConfigDto) -> ExportResult,
    private val limits: () -> ConfigDto,
    private val outboxRoot: File,
    private val kick: () -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    /** Sends the changed pages ([all] false, D47 B) or every non-empty page ([all] true). */
    suspend fun send(
        all: Boolean,
        caption: String?,
    ): SendOutcome =
        mutex.withLock {
            val cfg = limits()
            val notebook = board.notebook()
            val indices =
                when (val sel = selectPagesForSend(notebook, all, cfg.maxPages)) {
                    SendSelection.NothingToSend -> return@withLock SendOutcome.NothingToSend
                    is SendSelection.TooManyPages -> return@withLock SendOutcome.TooManyPages(sel.count, sel.max)
                    is SendSelection.Pages -> sel.indices
                }
            val parts = mutableListOf<PagePart>()
            for (i in indices) {
                val page = notebook.pages[i]
                when (val r = withContext(worker) { export(page, notebook.canvas, cfg) }) {
                    ExportResult.Empty -> {
                        Unit
                    }

                    is ExportResult.TooBig -> {
                        return@withLock SendOutcome.ExportFailed(i, r.reason)
                    }

                    is ExportResult.Exported -> {
                        val strokes = withContext(worker) { StrokesCodec.encode(notebook.canvas, page) }
                        parts += PagePart(i, r.png, strokes.takeIf { it.size <= cfg.maxStrokesBytes })
                    }
                }
            }
            if (parts.isEmpty()) return@withLock SendOutcome.NothingToSend
            val total = parts.sumOf { it.png.size.toLong() + (it.strokes?.size ?: 0) }
            if (total > cfg.maxUploadBytes) return@withLock SendOutcome.TooLarge(total, cfg.maxUploadBytes.toLong())

            val clientId = newId()
            val conversation = conversations.current()
            enqueue(
                clientId,
                OutboxKind.SKETCH,
                conversation.localId,
                threadKind = ThreadKind.SKETCH,
                text = caption,
                pageCount = parts.size,
            ) {
                parts.forEachIndexed { n, part ->
                    Payload.png(it, n).writeBytes(part.png)
                    part.strokes?.let { s -> Payload.strokes(it, n).writeBytes(s) }
                }
                Payload.writePages(it, clientId, caption, parts.map { p -> p.index }, notebook.canvas)
            }
            conversations.titleFromCaption(conversation.localId, caption)
            board.markSent(indices.associateWith { notebook.pages[it] })
            kick()
            SendOutcome.Queued(clientId)
        }

    /** Queues typed feedback for the selected conversation (Task 22). */
    suspend fun sendMessage(text: String): SendOutcome =
        mutex.withLock {
            val body = text.trim()
            if (body.isEmpty()) return@withLock SendOutcome.NothingToSend
            val clientId = newId()
            val conversation = conversations.current()
            enqueue(clientId, OutboxKind.MESSAGE, conversation.localId, ThreadKind.FEEDBACK, body, 0) {
                Payload.writeMessage(it, clientId, body)
            }
            kick()
            SendOutcome.Queued(clientId)
        }

    /** Queues a hand-off of the selected conversation's spec (Task 24); records the spec version it saw. */
    suspend fun enqueueHandoff(req: HandoffDto): SendOutcome =
        mutex.withLock {
            val conversation = conversations.current()
            val version = conversation.serverId?.let { db.specDao().latest(it)?.version }
            val target = (req.session as? JsonPrimitive)?.content ?: "preset ${req.session.presetName()}"
            val text = "Hand-off to $target" + (version?.let { " (spec v$it)" } ?: "")
            enqueue(req.clientId, OutboxKind.HANDOFF, conversation.localId, ThreadKind.HANDOFF, text, 0) {
                Payload.writeHandoff(it, req, version)
            }
            kick()
            SendOutcome.Queued(req.clientId)
        }

    @Suppress("LongParameterList")
    private suspend fun enqueue(
        clientId: String,
        kind: OutboxKind,
        conversationLocalId: String,
        threadKind: ThreadKind,
        text: String?,
        pageCount: Int,
        write: (File) -> Unit,
    ) {
        val dir = File(outboxRoot, clientId)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            write(dir)
        }
        val now = clock()
        var committed = false
        try {
            db.withTransaction {
                db.outboxDao().insert(
                    OutboxEntity(
                        clientId = clientId,
                        kind = kind,
                        conversationLocalId = conversationLocalId,
                        conversationServerId = null,
                        conversationMode = null,
                        payloadDir = dir.path,
                        state = OutboxState.PENDING,
                        attempts = 0,
                        nextAttemptAtMs = 0,
                        lastError = null,
                        serverId = null,
                    ),
                )
                db.threadDao().upsert(
                    ThreadItemEntity(
                        id = clientId,
                        conversationLocalId = conversationLocalId,
                        kind = threadKind,
                        clientId = clientId,
                        text = text,
                        pageCount = pageCount,
                        thumbDir = if (kind == OutboxKind.SKETCH) dir.path else null,
                        status = ThreadStatus.QUEUED,
                        createdAtMs = now,
                    ),
                )
                db.conversationDao().touch(conversationLocalId, now)
            }
            committed = true
        } finally {
            // No row, no payload: a failed transaction leaves nothing behind.
            if (!committed) withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private class PagePart(
        val index: Int,
        val png: ByteArray,
        val strokes: ByteArray?,
    )
}

private fun JsonElement.presetName(): String = (this as? JsonObject)?.get("preset")?.jsonPrimitive?.content ?: toString()
