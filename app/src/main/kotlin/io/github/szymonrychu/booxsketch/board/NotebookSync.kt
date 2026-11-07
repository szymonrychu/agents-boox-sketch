package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.RectF
import io.github.szymonrychu.booxsketch.api.SpecLayoutDto
import io.github.szymonrychu.booxsketch.data.FeedbackDao
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.data.SpecDao
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.notebook.LineOrigin
import io.github.szymonrychu.booxsketch.model.notebook.OpStatusModel
import io.github.szymonrychu.booxsketch.model.notebook.Placement
import io.github.szymonrychu.booxsketch.model.notebook.SpecLayoutModel
import io.github.szymonrychu.booxsketch.model.notebook.SpecLineModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * feedback, document notes) leave the board as it is; a version at or below the board's applied one is skipped. A
 * version is placed only while the board shows its conversation's notebook (or the unbound one it is about to adopt):
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
        val layout = spec.layout?.let(::layoutModel) ?: return@withLock
        val measure = board.textLayers.layer.measure
        while (true) {
            holds.awaitPenIdle()
            val (base, preview) = withContext(main) { board.notebook.value to board.placeVersion(version, spec.markdown, layout, measure) }
            val page = preview.notebook.pages[preview.notebook.current]
            val (bitmap, overlay) =
                withContext(worker) {
                    val rects = board.strikeRects(preview.notebook)
                    board.textLayers.prerender(page, rects) to rects
                }
            val done =
                withContext(main) {
                    // The board switched conversation (or applied this version) meanwhile: nothing to place.
                    stale() || commit(base, bitmap, overlay) { board.applyVersion(version, spec.markdown, layout, measure) }
                }
            if (done) {
                noteRejected(conversationServerId, version, base, layout)
                return@withLock
            }
        }
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
            val dirty = placed.changedRows[n.current]?.let { rows -> dirtyRect(NotebookGeometry(n.canvas.w, n.canvas.h), rows) }
            if (dirty != null || n.current != base.current) holds.surface?.swapText(shownStrokes(n), bitmap, dirty)
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
            val text =
                "Edit not applied ($what)" +
                    status.note
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ": $it" }
                        .orEmpty()
            val id = minOf(0, dao.minId(conversationServerId) ?: 0) - 1
            dao.upsert(FeedbackEntity(conversationServerId, id, "note", text, "page ${page + 1}", version, now()))
        }
    }

    private fun Notebook.showsConversation(localId: String) = conversationLocalId == null || conversationLocalId == localId

    private fun dirtyRect(
        g: NotebookGeometry,
        rows: IntRange,
    ) = RectF(0f, g.rowTop(rows.first).toFloat(), g.pageW.toFloat(), g.rowLineY(rows.last).toFloat())

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
