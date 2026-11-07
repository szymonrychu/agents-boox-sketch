package io.github.szymonrychu.booxsketch.send

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.HandoffDto
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.board.NotebookSync
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
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.StrokesCodec
import io.github.szymonrychu.booxsketch.model.notebook.AnnotationClassifier
import io.github.szymonrychu.booxsketch.model.notebook.EditOp
import io.github.szymonrychu.booxsketch.model.notebook.FreehandInsertMarks
import io.github.szymonrychu.booxsketch.model.notebook.ImageRegions
import io.github.szymonrychu.booxsketch.model.notebook.LayoutCodec
import io.github.szymonrychu.booxsketch.model.notebook.OpKind
import io.github.szymonrychu.booxsketch.model.notebook.PendingOp
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.RegionRef
import io.github.szymonrychu.booxsketch.model.notebook.Segmenter
import io.github.szymonrychu.booxsketch.model.notebook.TextMeasure
import io.github.szymonrychu.booxsketch.model.notebook.ToolInsertMarks
import io.github.szymonrychu.booxsketch.model.rectOf
import io.github.szymonrychu.booxsketch.model.selectPagesForSend
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.render.ExportResult
import io.github.szymonrychu.booxsketch.render.PageRasterExporter
import io.github.szymonrychu.booxsketch.render.RegionCropper
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

    /** Edit ops need the spec version they edit, and the conversation has none stored: nothing was written. */
    data object NoBaseVersion : SendOutcome

    /** Board page [page] has more new image regions than one upload takes per page ([max]): nothing was written. */
    data class TooManyRegions(
        val page: Int,
        val max: Int,
    ) : SendOutcome
}

/** What a send needs from the board: the notebook and marking pages sent. */
interface SendBoard {
    fun notebook(): Notebook

    /** Marks the sent pages, unless they changed since [sent] was taken. */
    suspend fun markSent(sent: Map<Int, Page>)

    /** Records the segments and edit ops just sent per page index: they wait for a spec version (the grey tick). */
    suspend fun addPending(
        pending: Map<Int, List<PendingSegment>>,
        ops: Map<Int, List<PendingOp>>,
    )
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
    /**
     * The text layer's measure: with it, the handwriting over text runs becomes edit ops (spec 4.3) before the rest is
     * segmented; without it (tests of plain pages) nothing is classified.
     */
    private val measure: TextMeasure? = null,
    private val classifier: AnnotationClassifier = AnnotationClassifier(listOf(ToolInsertMarks, FreehandInsertMarks)),
    /** Crops an image region of a board page (with its text layer) to a PNG, or null when it cannot fit the cap. */
    private val cropRegion: (Page, Stroke, Bitmap?, Canvas, ConfigDto) -> ByteArray? = RegionCropper(CanvasStrokeRenderer())::crop,
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
            // Read without creating a conversation: a refused send must leave none behind.
            val base = base(conversations.peekCurrent()?.serverId)
            val parts =
                when (val r = exportBoard(notebook, indices, cfg, clientId, base)) {
                    is Prepared.Failed -> return@withLock r.outcome
                    is Prepared.Parts -> r.parts
                }
            refusal(parts, base, cfg)?.let { return@withLock it }

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
                    part.layout?.let { l -> Payload.layout(it, n).writeBytes(l) }
                    part.regions.forEachIndexed { k, png -> Payload.region(it, n, k).writeBytes(png) }
                }
                Payload.writePages(it, clientId, caption, parts.map { p -> p.index }, notebook.canvas, baseVersion = base?.version)
            }
            conversations.titleFromCaption(conversation.localId, caption)
            board.markSent(indices.associateWith { notebook.pages[it] })
            val pending = parts.filter { it.pending.isNotEmpty() }.associate { it.index to it.pending }
            val ops = parts.filter { it.ops.isNotEmpty() }.associate { it.index to it.ops }
            // Stamped with the base version: a newer version that does not answer them releases them (never pending forever).
            val from = base?.version ?: 0
            val stamped = pending.mapValues { (_, l) -> l.map { it.copy(baseVersion = from) } }
            val stampedOps = ops.mapValues { (_, l) -> l.map { it.copy(baseVersion = from) } }
            if (pending.isNotEmpty() || ops.isNotEmpty()) board.addPending(stamped, stampedOps)
            kick()
            SendOutcome.Queued(clientId)
        }

    /** Why the exported board [parts] cannot be sent, or null when they can. */
    private fun refusal(
        parts: List<PagePart>,
        base: Base?,
        cfg: ConfigDto,
    ): SendOutcome? {
        if (parts.isEmpty()) return SendOutcome.NothingToSend
        // Ops edit the base version: runs are limited to its lines, so ops without one cannot occur.
        if (base == null && parts.any { it.ops.isNotEmpty() }) return SendOutcome.NoBaseVersion
        val total =
            parts.sumOf { p -> p.png.size.toLong() + (p.strokes?.size ?: 0) + (p.layout?.size ?: 0) + p.regions.sumOf { it.size } }
        return if (total > cfg.maxUploadBytes) SendOutcome.TooLarge(total, cfg.maxUploadBytes.toLong()) else null
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

    /**
     * Exports board pages [indices] of [notebook]: PNG, strokes and, for notebook pages, the layout with its segments
     * and image ops plus the region crops. Image ops need a [base] version (the connector requires `base_version` for
     * any op); without one the regions go as plain drawing. More than [MAX_REGIONS_PER_PAGE] new regions on a page
     * refuse the send.
     */
    private suspend fun exportBoard(
        notebook: Notebook,
        indices: List<Int>,
        cfg: ConfigDto,
        clientId: String,
        base: Base?,
    ): Prepared {
        val geometry = NotebookGeometry(notebook.canvas.w, notebook.canvas.h)
        for (i in indices) {
            if (ImageRegions.unsent(notebook.pages[i]).size > MAX_REGIONS_PER_PAGE) {
                return Prepared.Failed(SendOutcome.TooManyRegions(i, MAX_REGIONS_PER_PAGE))
            }
        }
        val parts = mutableListOf<PagePart>()
        for (i in indices) {
            val page = notebook.pages[i]
            val layer = withContext(worker) { boardPage?.invoke(page) }
            when (val r = withContext(worker) { exportBoardPage(page, layer, notebook.canvas, cfg) }) {
                ExportResult.Empty -> {
                    Unit
                }

                is ExportResult.TooBig -> {
                    return Prepared.Failed(SendOutcome.ExportFailed(i, r.reason))
                }

                is ExportResult.Exported -> {
                    val strokes = withContext(worker) { StrokesCodec.encode(notebook.canvas, page) }
                    val kept = strokes.takeIf { it.size <= cfg.maxStrokesBytes }
                    val n = parts.size
                    // The layout's stroke indices point into the strokes part: no strokes part, no layout and no ops.
                    val images =
                        if (kept == null || base == null) {
                            emptyList()
                        } else {
                            regions(page, n, base.lines, layer, notebook.canvas, cfg)
                                ?: return Prepared.Failed(SendOutcome.ExportFailed(i, REGION_TOO_BIG))
                        }
                    val notebookPart =
                        kept?.let {
                            withContext(worker) {
                                notebookLayout(clientId, page, geometry, n, base?.lines.orEmpty(), images.map { it.first })
                            }
                        }
                    val ops = notebookPart?.ops.orEmpty()
                    parts += PagePart(i, r.png, kept, notebookPart?.bytes, notebookPart?.segments.orEmpty(), ops, images.map { it.second })
                }
            }
        }
        return Prepared.Parts(parts)
    }

    /**
     * One image op per region of [page] not sent yet (spec 4.3 rule 1) with its crop: the region's rect `[x, y, w, h]`
     * in page px, its members as strokes, part `region.<part>.<k>.png` and, as `after_line`, the run of a [baseLines]
     * line ending nearest above the rect. Null when a region is degenerate or its crop cannot fit the PNG cap.
     */
    @Suppress("LongParameterList")
    private suspend fun regions(
        page: Page,
        part: Int,
        baseLines: Set<String>,
        layer: Bitmap?,
        canvas: Canvas,
        cfg: ConfigDto,
    ): List<Pair<EditOp, ByteArray>>? {
        val geometry = NotebookGeometry(canvas.w, canvas.h)
        return ImageRegions.unsent(page).mapIndexed { k, region ->
            val r = rectOf(region)
            if (r.r <= r.l || r.b <= r.t) return null
            val above =
                page.text
                    .filter { it.lineId in baseLines && geometry.rowTop(it.row + it.wrapRows) <= r.t + geometry.rowPitch / 2f }
                    .maxByOrNull { it.row + it.wrapRows }
            val op =
                EditOp(
                    id = newId(),
                    kind = OpKind.IMAGE,
                    strokeIds = ImageRegions.members(page, region),
                    rect = listOf(r.l, r.t, r.r - r.l, r.b - r.t),
                    region = RegionRef(newId(), "region.$part.$k.png", above?.lineId),
                )
            op to (withContext(worker) { cropRegion(page, region, layer, canvas, cfg) } ?: return null)
        }
    }

    private fun exportBoardPage(
        page: Page,
        layer: Bitmap?,
        canvas: Canvas,
        cfg: ConfigDto,
    ): ExportResult = layer?.let { exportPage(it, page, canvas, cfg) } ?: export(page, canvas, cfg)

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

    /**
     * Queues a revert of the conversation [conversationLocalId] to spec [version] (Q15 B) with [latest], the newest
     * version the app shows, as the base: the connector copies it as version latest + 1, or answers 409 `stale_base`
     * (a newer version exists) or `busy` (a translation runs), which fail the item with a reason. Reverting the latest
     * version is refused.
     */
    suspend fun revert(
        conversationLocalId: String,
        version: Int,
        latest: Int,
    ): SendOutcome =
        mutex.withLock {
            if (version >= latest || version < 1) return@withLock SendOutcome.NothingToSend
            val clientId = newId()
            enqueue(clientId, OutboxKind.REVERT, conversationLocalId, ThreadKind.REVERT, "Revert to v$version", 0) {
                Payload.writeRevert(it, version, latest)
            }
            kick()
            SendOutcome.Queued(clientId)
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
     * The `layout.<part>.json` of a notebook page with the segments and edit ops it sends, or null for a page with no
     * new handwriting rows, text runs or [imageOps] (plain drawing needs no layout). Only runs whose line id is in
     * [baseLines] are sent and annotated: the connector rejects (422) any run or op line id the base version's layout
     * does not have. The ink inside image regions is the picture of its [imageOps]: never classified, never a segment.
     * The other handwriting over those runs is classified first ([AnnotationClassifier]); its strokes are in no
     * segment. Segment ids carry the upload part index. The image ops follow the classified ones.
     */
    @Suppress("LongParameterList")
    private fun notebookLayout(
        clientId: String,
        page: Page,
        geometry: NotebookGeometry,
        part: Int,
        baseLines: Set<String>,
        imageOps: List<EditOp>,
    ): NotebookPart? {
        val runs = page.text.filter { it.lineId in baseLines }
        val shown = page.copy(text = runs)
        val inRegions = ImageRegions.excluded(page)
        val classified = measure?.let { classifier.classify(shown, geometry, it, inRegions, newId) }
        val segments =
            Segmenter.segment(page, geometry, part, exclude = inRegions + classified?.opStrokeIds.orEmpty()).segments.map {
                PendingSegment(clientId, it.id, it.row, it.x0, it.x1, it.strokeIds)
            }
        val ops = classified?.ops.orEmpty() + imageOps
        if (segments.isEmpty() && runs.isEmpty() && ops.isEmpty()) return null
        return NotebookPart(LayoutCodec.encode(shown, geometry, segments, ops), segments, ops.map { PendingOp(clientId, it) })
    }

    private class NotebookPart(
        val bytes: ByteArray,
        val segments: List<PendingSegment>,
        val ops: List<PendingOp>,
    )

    /**
     * `base_version`: the conversation's latest stored spec version, with the line ids of its layout (none when it has
     * no layout). The connector checks base_version is the latest and every run line id against that version's
     * layout, and the translator keeps line ids relative to it. Null for a conversation without a server id or a
     * stored version.
     */
    private suspend fun base(serverId: String?): Base? {
        val latest = serverId?.let { db.specDao().latest(it) } ?: return null
        val lines =
            latest.layout
                ?.let {
                    NotebookSync
                        .layoutModel(it)
                        .lines
                        .map { l -> l.lineId }
                        .toSet()
                }.orEmpty()
        return Base(latest.version, lines)
    }

    private class Base(
        val version: Int,
        val lines: Set<String>,
    )

    private class PagePart(
        val index: Int,
        val png: ByteArray,
        val strokes: ByteArray?,
        val layout: ByteArray? = null,
        val pending: List<PendingSegment> = emptyList(),
        val ops: List<PendingOp> = emptyList(),
        /** Region crops; crop k is the part `region.<part>.<k>.png`. */
        val regions: List<ByteArray> = emptyList(),
    )

    companion object {
        /** Image regions per page and upload: the connector's default `max_regions_per_page`, not in `/config` yet. */
        const val MAX_REGIONS_PER_PAGE = 4

        private const val REGION_TOO_BIG = "image region does not fit the PNG size cap"
    }
}

private const val PAGE_DPI = 300f

private fun JsonElement.presetName(): String = (this as? JsonObject)?.get("preset")?.jsonPrimitive?.content ?: toString()
