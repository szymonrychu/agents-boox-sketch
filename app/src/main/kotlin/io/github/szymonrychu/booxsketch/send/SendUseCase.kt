package io.github.szymonrychu.booxsketch.send

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import io.github.szymonrychu.booxsketch.documents.AnnotatedInput
import io.github.szymonrychu.booxsketch.documents.AnnotationSend
import io.github.szymonrychu.booxsketch.documents.AnnotationStore
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.SendSelection
import io.github.szymonrychu.booxsketch.model.StrokesCodec
import io.github.szymonrychu.booxsketch.model.notebook.LayoutCodec
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.Segmenter
import io.github.szymonrychu.booxsketch.model.selectPagesForSend
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.render.ExportResult
import io.github.szymonrychu.booxsketch.render.PageRasterExporter
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

    /** The notes belong to spec [version] but [latest] exists: nothing was written. */
    data class StaleVersion(
        val version: Int,
        val latest: Int,
    ) : SendOutcome

    /** The draft of this version was sent already (a repeated tap). */
    data object AlreadySent : SendOutcome
}

/** What a send needs from the board: the notebook and marking pages sent. */
interface SendBoard {
    fun notebook(): Notebook

    /** Marks the sent pages, unless they changed since [sent] was taken. */
    suspend fun markSent(sent: Map<Int, Page>)

    /** Records the segments just sent per page index: they wait for a spec version (the grey tick). */
    suspend fun addPending(pending: Map<Int, List<PendingSegment>>)
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
    /** Frozen pages and sent markers of documents; only [sendAnnotation] needs it. */
    private val annotations: AnnotationStore? = null,
    private val exportPage: (Bitmap, Page, Canvas, ConfigDto) -> ExportResult = PageRasterExporter(CanvasStrokeRenderer())::export,
    /**
     * The text layer of a board page. When given, every board page is exported whole (spec 5.1: the text layer and
     * the visible ink, no grid) through [exportPage], so the PNG matches the page px of its layout; else [export].
     */
    private val boardPage: ((Page) -> Bitmap)? = null,
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
            val clientId = newId()
            val parts =
                when (val r = exportBoard(notebook, indices, cfg, clientId)) {
                    is Prepared.Failed -> return@withLock r.outcome
                    is Prepared.Parts -> r.parts
                }
            if (parts.isEmpty()) return@withLock SendOutcome.NothingToSend
            val total = parts.sumOf { it.png.size.toLong() + (it.strokes?.size ?: 0) + (it.layout?.size ?: 0) }
            if (total > cfg.maxUploadBytes) return@withLock SendOutcome.TooLarge(total, cfg.maxUploadBytes.toLong())

            val conversation = conversations.current()
            val baseVersion = baseVersion(conversation.serverId, notebook, indices)
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
                    part.layout?.let { l -> Payload.layout(it, n).writeBytes(l) }
                }
                Payload.writePages(it, clientId, caption, parts.map { p -> p.index }, notebook.canvas, baseVersion = baseVersion)
            }
            conversations.titleFromCaption(conversation.localId, caption)
            board.markSent(indices.associateWith { notebook.pages[it] })
            val pending = parts.filter { it.pending.isNotEmpty() }.associate { it.index to it.pending }
            if (pending.isNotEmpty()) board.addPending(pending)
            kick()
            SendOutcome.Queued(clientId)
        }

    /**
     * Sends the handwritten notes of a document draft: only the pages with ink, as full-page PNGs (frozen page plus ink)
     * with `meta.annotates`. Refused, writing nothing, when [version] is not [latestVersion]; a repeated tap after the
     * draft was queued returns [SendOutcome.AlreadySent]. More inked pages than the server takes per upload become
     * several uploads in page order. The draft is marked sent only after every upload is queued.
     */
    suspend fun sendAnnotation(
        conversationLocalId: String,
        version: Int,
        latestVersion: Int,
        pages: List<AnnotatedInput>,
        caption: String?,
    ): SendOutcome =
        mutex.withLock {
            val store = checkNotNull(annotations) { "annotation store not configured" }
            if (version != latestVersion) return@withLock SendOutcome.StaleVersion(version, latestVersion)
            if (store.isSent(conversationLocalId, version)) return@withLock SendOutcome.AlreadySent
            val cfg = limits()
            val uploads = AnnotationSend.plan(pages, cfg.maxPages)
            if (uploads.isEmpty()) return@withLock SendOutcome.NothingToSend
            val prepared = ArrayList<List<PagePart>>()
            for (upload in uploads) {
                when (val r = exportUpload(upload, cfg)) {
                    is Prepared.Failed -> return@withLock r.outcome
                    is Prepared.Parts -> prepared += r.parts
                }
                val total = prepared.last().sumOf { it.png.size.toLong() + (it.strokes?.size ?: 0) }
                if (total > cfg.maxUploadBytes) return@withLock SendOutcome.TooLarge(total, cfg.maxUploadBytes.toLong())
            }
            val text = AnnotationSend.caption(version, caption)
            var first: String? = null
            uploads.forEachIndexed { n, upload ->
                val clientId = newId()
                if (first == null) first = clientId
                val parts = prepared[n]
                enqueue(clientId, OutboxKind.SKETCH, conversationLocalId, ThreadKind.SKETCH, text, parts.size) {
                    parts.forEachIndexed { i, part ->
                        Payload.png(it, i).writeBytes(part.png)
                        part.strokes?.let { s -> Payload.strokes(it, i).writeBytes(s) }
                    }
                    Payload.writePages(
                        it,
                        clientId,
                        text,
                        upload.map { u ->
                            u.index
                        },
                        canvasOf(upload),
                        AnnotationSend.annotates(version, upload),
                    )
                }
            }
            store.markSent(conversationLocalId, version)
            kick()
            SendOutcome.Queued(checkNotNull(first))
        }

    /** Exports board pages [indices] of [notebook]: PNG, strokes and, for notebook pages, the layout and its segments. */
    private suspend fun exportBoard(
        notebook: Notebook,
        indices: List<Int>,
        cfg: ConfigDto,
        clientId: String,
    ): Prepared {
        val geometry = NotebookGeometry(notebook.canvas.w, notebook.canvas.h)
        val parts = mutableListOf<PagePart>()
        for (i in indices) {
            val page = notebook.pages[i]
            when (val r = withContext(worker) { exportBoardPage(page, notebook.canvas, cfg) }) {
                ExportResult.Empty -> {
                    Unit
                }

                is ExportResult.TooBig -> {
                    return Prepared.Failed(SendOutcome.ExportFailed(i, r.reason))
                }

                is ExportResult.Exported -> {
                    val strokes = withContext(worker) { StrokesCodec.encode(notebook.canvas, page) }
                    val kept = strokes.takeIf { it.size <= cfg.maxStrokesBytes }
                    // The layout's stroke indices point into the strokes part: no strokes part, no layout.
                    val notebookPart = kept?.let { withContext(worker) { notebookLayout(clientId, page, geometry, parts.size) } }
                    parts += PagePart(i, r.png, kept, notebookPart?.first, notebookPart?.second.orEmpty())
                }
            }
        }
        return Prepared.Parts(parts)
    }

    private fun exportBoardPage(
        page: Page,
        canvas: Canvas,
        cfg: ConfigDto,
    ): ExportResult = boardPage?.let { exportPage(it(page), page, canvas, cfg) } ?: export(page, canvas, cfg)

    private fun canvasOf(upload: List<AnnotatedInput>): Canvas {
        val size =
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                BitmapFactory.decodeFile(
                    upload.first().background.path,
                    it,
                )
            }
        return Canvas(size.outWidth, size.outHeight, PAGE_DPI)
    }

    private sealed interface Prepared {
        class Parts(
            val parts: List<PagePart>,
        ) : Prepared

        class Failed(
            val outcome: SendOutcome,
        ) : Prepared
    }

    private suspend fun exportUpload(
        upload: List<AnnotatedInput>,
        cfg: ConfigDto,
    ): Prepared {
        val canvas = canvasOf(upload)
        val parts = ArrayList<PagePart>()
        for (input in upload) {
            val background = withContext(worker) { BitmapFactory.decodeFile(input.background.path) }
            if (background == null) {
                return Prepared.Failed(SendOutcome.ExportFailed(input.index, "page image missing"))
            }
            when (val r = withContext(worker) { exportPage(background, input.page, canvas, cfg) }) {
                ExportResult.Empty -> {
                    Unit
                }

                is ExportResult.TooBig -> {
                    return Prepared.Failed(SendOutcome.ExportFailed(input.index, r.reason))
                }

                is ExportResult.Exported -> {
                    val strokes = withContext(worker) { StrokesCodec.encode(canvas, input.page) }
                    parts += PagePart(input.index, r.png, strokes.takeIf { it.size <= cfg.maxStrokesBytes })
                }
            }
        }
        return Prepared.Parts(parts)
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

    /**
     * The `layout.<part>.json` of a notebook page and the segments it sends, or null for a page with neither new
     * handwriting rows nor text runs (plain drawing needs no layout). Segment ids carry the upload part index.
     */
    private fun notebookLayout(
        clientId: String,
        page: Page,
        geometry: NotebookGeometry,
        part: Int,
    ): Pair<ByteArray, List<PendingSegment>>? {
        val segments =
            Segmenter.segment(page, geometry, part).segments.map {
                PendingSegment(clientId, it.id, it.row, it.x0, it.x1, it.strokeIds)
            }
        if (segments.isEmpty() && page.text.isEmpty()) return null
        return LayoutCodec.encode(page, geometry, segments, emptyList()) to segments
    }

    /**
     * `base_version`: the conversation's latest stored spec version (what the board shows once synced), else the newest
     * version of the sent pages' runs; null for a conversation without a server id (base_version needs one).
     */
    private suspend fun baseVersion(
        serverId: String?,
        notebook: Notebook,
        indices: List<Int>,
    ): Int? {
        if (serverId == null) return null
        return db.specDao().latest(serverId)?.version ?: indices.flatMap { notebook.pages[it].text }.maxOfOrNull { it.specVersion }
    }

    private class PagePart(
        val index: Int,
        val png: ByteArray,
        val strokes: ByteArray?,
        val layout: ByteArray? = null,
        val pending: List<PendingSegment> = emptyList(),
    )
}

private const val PAGE_DPI = 300f

private fun JsonElement.presetName(): String = (this as? JsonObject)?.get("preset")?.jsonPrimitive?.content ?: toString()
