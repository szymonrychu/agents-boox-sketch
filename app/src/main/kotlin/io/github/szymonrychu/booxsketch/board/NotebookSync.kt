package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import io.github.szymonrychu.booxsketch.api.SpecLayoutDto
import io.github.szymonrychu.booxsketch.data.FeedbackDao
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.data.SpecDao
import io.github.szymonrychu.booxsketch.data.ThreadDao
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.notebook.LineOrigin
import io.github.szymonrychu.booxsketch.model.notebook.OpKind
import io.github.szymonrychu.booxsketch.model.notebook.OpStatusModel
import io.github.szymonrychu.booxsketch.model.notebook.Placement
import io.github.szymonrychu.booxsketch.model.notebook.SpecLayoutModel
import io.github.szymonrychu.booxsketch.model.notebook.SpecLineModel
import io.github.szymonrychu.booxsketch.model.notebook.TextMeasure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import io.github.szymonrychu.booxsketch.model.RectF as ModelRect

/**
 * Applies stored spec versions to the board (spec 6). A version with a layout is placed by
 * [io.github.szymonrychu.booxsketch.model.notebook.RunPlacer] on the main thread (cheap, pure), the selected page's
 * text layer is rendered on [worker], and the result reaches the board through one hold at pen idle
 * ([TextLayerHolds]): the new notebook state and the bitmap swap happen together while raw drawing is off, so the
 * hidden ink and the new text appear in one frame and the pen path is never touched while raw drawing is live. A
 * board that changed while the layer rendered (the user wrote on) is placed again. Versions without a layout (typed
 * feedback, document notes) leave the board as it is, except that they release the pending marks of older uploads
 * (the strokes become plain unsent ink, see [io.github.szymonrychu.booxsketch.model.notebook.RunPlacer.releasePending]);
 * a version at or below the board's applied one is skipped. A version is placed only while the board shows its
 * conversation's notebook (or the unbound one it is about to adopt):
 * the check runs in the same main-thread step as the placement, so a conversation switch never gets another's text.
 * The strike preview ([StrikePreviewSync]) is recomputed for the placed page and swapped in with the text, and the
 * notes of rejected edit ops go to the feedback strip.
 */
class NotebookSync(
    private val specs: SpecDao,
    private val board: BoardViewModel,
    private val holds: TextLayerHolds,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    /** Where the notes of rejected ops go (the feedback strip); none in tests that do not look at them. */
    private val feedback: FeedbackDao? = null,
    private val now: () -> String = { Instant.now().toString() },
    /** Links a version's sketch ids back to the uploads (client ids) that sent them. */
    private val threads: ThreadDao? = null,
) {
    private val mutex = Mutex()

    /** Applies every stored version of [conversationServerId] (local id [conversationLocalId]) newer than the board's, in order. */
    suspend fun catchUp(
        conversationServerId: String,
        conversationLocalId: String,
    ) {
        val applied = withContext(main) { board.notebook.value.appliedSpecVersion ?: 0 }
        specs
            .versions(conversationServerId)
            .filter { it > applied }
            .sorted()
            .forEach { onVersion(conversationServerId, conversationLocalId, it) }
    }

    suspend fun onVersion(
        conversationServerId: String,
        conversationLocalId: String,
        version: Int,
    ) = mutex.withLock {
        fun stale() = board.notebook.value.let { n -> !n.showsConversation(conversationLocalId) || (n.appliedSpecVersion ?: 0) >= version }
        if (withContext(main) { stale() }) return@withLock
        val spec = specs.get(conversationServerId, version) ?: return@withLock
        val layout = spec.layout?.let(::layoutModel)
        val measure = board.textLayers.layer.measure
        // Only the uploads this version was made from can be released by it; a later one on the same base is still in flight.
        val answered = answeredUploads(spec.sketchIds)
        while (true) {
            holds.awaitPenIdle()
            val (base, preview) =
                withContext(main) {
                    board.notebook.value to board.preview(version, spec.markdown, layout, measure, answered)
                }
            // A version without a layout only releases the pending marks of older uploads; nothing pending, nothing to do.
            if (layout == null && preview.notebook === base) return@withLock
            val page = preview.notebook.pages[preview.notebook.current]
            val (bitmap, overlay) =
                withContext(worker) {
                    val rects = board.strikeRects(preview.notebook)
                    board.textLayers.prerender(page, rects) to rects
                }
            val outcome =
                withContext(main) {
                    // The board switched conversation (or applied this version) meanwhile: nothing to place.
                    if (stale()) {
                        null
                    } else {
                        commit(base, bitmap, overlay) { board.place(version, spec.markdown, layout, measure, answered) }
                    }
                }
            if (outcome == null) return@withLock
            if (outcome) {
                layout?.let { noteRejected(conversationServerId, version, base, it) }
                return@withLock
            }
        }
    }

    /** The placement of [version] on the shown board, or only the release of older pending marks for one without a [layout]. */
    private fun BoardViewModel.preview(
        version: Int,
        markdown: String,
        layout: SpecLayoutModel?,
        measure: TextMeasure,
        answered: Set<String>,
    ) = layout?.let { placeVersion(version, markdown, it, measure, answered) } ?: previewRelease(version, answered, markdown)

    private fun BoardViewModel.place(
        version: Int,
        markdown: String,
        layout: SpecLayoutModel?,
        measure: TextMeasure,
        answered: Set<String>,
    ) = layout?.let { applyVersion(version, markdown, it, measure, answered) } ?: releasePending(version, answered, markdown)

    /** The client ids of the uploads whose stored sketch id is among the [sketchIds] (JSON list) a version was made from. */
    private suspend fun answeredUploads(sketchIds: String): Set<String> {
        val dao = threads ?: return emptySet()
        val ids = runCatching { Json.decodeFromString(ListSerializer(String.serializer()), sketchIds) }.getOrDefault(emptyList())
        return ids.mapNotNull { dao.byServerId(it)?.clientId }.toSet()
    }

    /**
     * Places through [apply] and swaps [bitmap] in under one hold, unless the board changed since [base] was placed or
     * the pen is busy (the user wrote meanwhile, or is writing): then false, to place again after the next idle.
     */
    private fun commit(
        base: Notebook,
        bitmap: Bitmap?,
        overlay: List<ModelRect>,
        apply: () -> Placement,
    ): Boolean {
        if (board.notebook.value !== base || !holds.idleNow()) return false
        holds.hold {
            val placed = apply()
            val n = placed.notebook
            board.setStrikeOverlay(n.current, overlay)
            // The whole page is drawn again (null dirty rect), never only the changed rows over an earlier render.
            if (n.current in placed.changedRows || n.current != base.current) {
                holds.surface?.swapText(shownStrokes(n), bitmap, null)
                holds.ui?.uiChanged()
            }
        }
        return true
    }

    /**
     * The note of each op the version rejected that this board sent (spec 5.3: rejected ops keep their ink) goes to the
     * feedback strip as a local item: a negative id, so it never moves the connector cursor ([FeedbackDao.maxId]).
     */
    private suspend fun noteRejected(
        conversationServerId: String,
        version: Int,
        base: Notebook,
        layout: SpecLayoutModel,
    ) {
        val dao = feedback ?: return
        val sent =
            base.pages
                .withIndex()
                .flatMap { (i, p) -> p.pendingOps.map { it.op.id to (i to it.op) } }
                .toMap()
        for (status in layout.ops.filter { !it.applied }) {
            val (page, op) = sent[status.id] ?: continue
            val what = op.target?.let { "${op.kind.name.lowercase()} \"${it.quote}\"" } ?: op.kind.name.lowercase()
            val reason =
                status.note
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ": $it" }
                    .orEmpty()
            val text = if (op.kind == OpKind.NOTE) "Note not applied$reason" else "Edit not applied ($what)$reason"
            val id = minOf(0, dao.minId(conversationServerId) ?: 0) - 1
            dao.upsert(FeedbackEntity(conversationServerId, id, "note", text, "page ${page + 1}", version, now()))
        }
    }

    private fun Notebook.showsConversation(localId: String) = conversationLocalId == null || conversationLocalId == localId

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The stored `SpecEntity.layout` JSON as the model the placer takes. */
        fun layoutModel(stored: String): SpecLayoutModel {
            val dto = json.decodeFromString(SpecLayoutDto.serializer(), stored)
            return SpecLayoutModel(
                lines = dto.lines.map { SpecLineModel(it.lineId, it.sourceLine, origin(it.origin)) },
                ops = dto.ops.map { OpStatusModel(it.id, it.status == "applied", it.note) },
                baseVersion = dto.baseVersion,
                revertedFrom = dto.revertedFrom,
            )
        }

        private fun origin(o: JsonObject?): LineOrigin? {
            val segments = (o?.get("segments") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val kept = (o?.get("kept") as? JsonPrimitive)?.contentOrNull
            val op = (o?.get("op") as? JsonPrimitive)?.contentOrNull
            return when {
                segments != null -> LineOrigin.Segments(segments)
                kept != null -> LineOrigin.Kept(kept)
                op != null -> LineOrigin.Op(op)
                else -> null
            }
        }
    }
}
