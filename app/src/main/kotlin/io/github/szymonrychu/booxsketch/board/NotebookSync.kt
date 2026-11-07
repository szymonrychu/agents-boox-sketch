package io.github.szymonrychu.booxsketch.board

import android.graphics.RectF
import io.github.szymonrychu.booxsketch.api.SpecLayoutDto
import io.github.szymonrychu.booxsketch.data.SpecDao
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.notebook.LineOrigin
import io.github.szymonrychu.booxsketch.model.notebook.OpStatusModel
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

/**
 * Applies stored spec versions to the board (spec 6). A version with a layout is placed by
 * [io.github.szymonrychu.booxsketch.model.notebook.RunPlacer] on the main thread (cheap, pure), the selected page's
 * text layer is rendered on [worker], and the result reaches the board through one hold at pen idle
 * ([TextLayerHolds]): the new notebook state and the bitmap swap happen together while raw drawing is off, so the
 * hidden ink and the new text appear in one frame and the pen path is never touched while raw drawing is live. A
 * board that changed while the layer rendered (the user wrote on) is placed again. Versions without a layout (typed
 * feedback, document notes) leave the board as it is; a version at or below the board's applied one is skipped.
 */
class NotebookSync(
    private val specs: SpecDao,
    private val board: BoardViewModel,
    private val holds: TextLayerHolds,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()

    /** Applies every stored version of [conversationServerId] newer than the board's, in order. */
    suspend fun catchUp(conversationServerId: String) {
        val applied = withContext(main) { board.notebook.value.appliedSpecVersion ?: 0 }
        specs
            .versions(conversationServerId)
            .filter { it > applied }
            .sorted()
            .forEach { onVersion(conversationServerId, it) }
    }

    suspend fun onVersion(
        conversationServerId: String,
        version: Int,
    ) = mutex.withLock {
        if (withContext(main) { (board.notebook.value.appliedSpecVersion ?: 0) >= version }) return@withLock
        val spec = specs.get(conversationServerId, version) ?: return@withLock
        val layout = spec.layout?.let(::layoutModel) ?: return@withLock
        val measure = board.textLayers.layer.measure
        while (true) {
            holds.awaitPenIdle()
            val (base, preview) =
                withContext(main) {
                    board.notebook.value.let {
                        it to
                            board.placeVersion(version, spec.markdown, layout, measure)
                    }
                }
            val page = preview.notebook.pages[preview.notebook.current]
            val bitmap = withContext(worker) { board.textLayers.prerender(page) }
            val done =
                withContext(main) {
                    // The user wrote meanwhile, or is writing: place again on the new state, after the next idle.
                    if (board.notebook.value !== base || !holds.idleNow()) return@withContext false
                    holds.hold {
                        val placed = board.applyVersion(version, spec.markdown, layout, measure)
                        val n = placed.notebook
                        val dirty = placed.changedRows[n.current]?.let { rows -> dirtyRect(NotebookGeometry(n.canvas.w, n.canvas.h), rows) }
                        if (dirty != null || n.current != base.current) holds.surface?.swapText(shownStrokes(n), bitmap, dirty)
                    }
                    true
                }
            if (done) return@withLock
        }
    }

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
