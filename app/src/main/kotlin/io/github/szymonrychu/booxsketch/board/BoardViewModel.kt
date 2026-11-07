package io.github.szymonrychu.booxsketch.board

import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Command
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.UndoStack
import io.github.szymonrychu.booxsketch.model.hitStrokes
import io.github.szymonrychu.booxsketch.model.notebook.PendingOp
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.Placement
import io.github.szymonrychu.booxsketch.model.notebook.RunPlacer
import io.github.szymonrychu.booxsketch.model.notebook.SpecLayoutModel
import io.github.szymonrychu.booxsketch.model.notebook.TextMeasure
import io.github.szymonrychu.booxsketch.model.snapShape
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Board state: the notebook, pen settings and per-page undo. Autosaves 500 ms after the last change. */
@Suppress("TooManyFunctions") // one small intent method per board action (tools, colours, pages, undo, send)
class BoardViewModel(
    private var store: NotebookStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultCanvas: Canvas = DEFAULT_CANVAS,
    /** The notebook files per conversation; needed by [bindConversation]. */
    private val files: NotebookFiles? = null,
) {
    private var undoStack = UndoStack()
    private var saveJob: Job? = null

    /** Serialises [bindConversation], [dropFailedUpload] and [conversationGone]: each reads and writes notebook files. */
    private val bindLock = Mutex()

    private val _notebook = MutableStateFlow(store.load() ?: Notebook(defaultCanvas, listOf(Page(emptyList(), false)), 0))
    val notebook: StateFlow<Notebook> = _notebook.asStateFlow()

    private val _tool = MutableStateFlow(Tool.BALLPOINT)
    val tool: StateFlow<Tool> = _tool.asStateFlow()

    private val _width = MutableStateFlow(DEFAULT_WIDTH)
    val width: StateFlow<Float> = _width.asStateFlow()

    private val _effectiveWidth = MutableStateFlow(DEFAULT_WIDTH)

    /** The width the input draws with: [SHAPE_WIDTH] while a shape tool is selected, else the slider [width]. */
    val effectiveWidth: StateFlow<Float> = _effectiveWidth.asStateFlow()

    private val _eraser = MutableStateFlow(false)
    val eraser: StateFlow<Boolean> = _eraser.asStateFlow()

    private val _color = MutableStateFlow(PenColor.BLACK)
    val color: StateFlow<PenColor> = _color.asStateFlow()

    private val _preview = MutableStateFlow<Stroke?>(null)

    /** The snapped shape the current drag would commit, null when no shape drag is active. */
    val preview: StateFlow<Stroke?> = _preview.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** The selected page's text bitmap; created on first use (it needs Android graphics). */
    val textLayers: TextLayerCache by lazy { TextLayerCache(TextLayer(NotebookGeometry(defaultCanvas.w, defaultCanvas.h))) }

    /** When a new text layer may reach the board: one hold at pen idle. */
    val textHolds: TextLayerHolds by lazy { TextLayerHolds() }

    private val _saveError = MutableStateFlow<String?>(null)

    /** Message of the last failed save, null once a save succeeded again. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun setTool(tool: Tool) {
        _tool.value = tool
        _eraser.value = false
        updateEffectiveWidth()
    }

    fun setColor(color: PenColor) {
        _color.value = color
        _eraser.value = false
    }

    fun setWidth(width: Float) {
        _width.value = width
        updateEffectiveWidth()
    }

    private fun updateEffectiveWidth() {
        _effectiveWidth.value = if (_tool.value.isShape) SHAPE_WIDTH else _width.value
    }

    fun setEraser(on: Boolean) {
        _eraser.value = on
    }

    private val currentStrokes: List<Stroke>
        get() = _notebook.value.let { it.pages[it.current].strokes }

    /** Shape tools: previews the snapped shape for a drag in canvas pixels (same call as the commit). */
    fun onShapeDrag(drag: Stroke) {
        _preview.value = snapShape(drag.withShapeWidth(), currentStrokes)
    }

    fun onShapeCancel() {
        _preview.value = null
    }

    /** Commits a stroke; a shape-tool drag is snapped like its preview and dropped when too small. */
    fun onStroke(stroke: Stroke) {
        if (!stroke.tool.isShape) return update { undoStack.apply(it, Command.AddStroke(stroke)) }
        _preview.value = null
        val shape = snapShape(stroke.withShapeWidth(), currentStrokes) ?: return
        update { undoStack.apply(it, Command.AddStroke(shape)) }
    }

    private fun Stroke.withShapeWidth() = if (width == SHAPE_WIDTH) this else copy(width = SHAPE_WIDTH)

    /** Erases the visible strokes (not consumed ones or applied region frames) hit by a circle at (x, y), in canvas px; undoable. */
    fun onErase(
        x: Float,
        y: Float,
        radius: Float,
    ) = update { n ->
        val ids = hitStrokes(n.pages[n.current].visibleStrokes, x, y, radius)
        if (ids.isEmpty()) n else undoStack.apply(n, Command.EraseStrokes(ids))
    }

    fun undo() = update { undoStack.undo(it) }

    fun redo() = update { undoStack.redo(it) }

    fun clear() = update { undoStack.apply(it, Command.Clear) }

    fun addPage() = update { it.addPage() }

    /** Deletes page [i] through the undo stack so every page keeps its own history. */
    fun deletePage(i: Int) = update { undoStack.deletePage(it, i) }

    fun selectPage(i: Int) = update { it.select(i) }

    /** Marks pages as sent (after a successful upload). */
    fun markSent(indices: Set<Int>) = update { it.markSent(indices) }

    /**
     * Marks the pages of a send snapshot as sent, but only those still equal to the snapshot, so
     * a stroke drawn while the send was being prepared keeps its page marked as changed.
     */
    fun markSentIfUnchanged(sent: Map<Int, Page>) =
        update { n -> n.markSent(sent.filter { (i, page) -> n.pages.getOrNull(i) == page }.keys) }

    /** Records the segments of an upload: they wait for a spec version (grey tick on their rows). */
    fun addPending(
        pending: Map<Int, List<PendingSegment>>,
        ops: Map<Int, List<PendingOp>> = emptyMap(),
    ) = update { it.addPending(pending, ops) }

    /** The upload [clientId] failed: its pending segments go, its pages count as changed again (marks kept). */
    fun dropPending(clientId: String) = update { it.dropPending(clientId) }

    /**
     * The upload [clientId] of conversation [conversationLocalId] failed: [dropPending] on the shown notebook when it
     * holds the upload or belongs to that conversation, else on that conversation's saved notebook, so its ticks
     * clear and its ink is re-sent when the user goes back to it.
     */
    suspend fun dropFailedUpload(
        clientId: String,
        conversationLocalId: String,
    ) = bindLock.withLock {
        val shown = _notebook.value
        val holds =
            shown.pages.any { p ->
                p.pendingSegments.any { it.clientId == clientId } || p.pendingOps.any { it.clientId == clientId }
            }
        if (holds || shown.conversationLocalId == conversationLocalId) return@withLock dropPending(clientId)
        val saved = files?.store(conversationLocalId) ?: return@withLock
        try {
            withContext(ioDispatcher) { saved.load()?.let { n -> n.dropPending(clientId).takeIf { it != n }?.let(saved::save) } }
        } catch (e: IOException) {
            _saveError.value = e.message ?: e.javaClass.simpleName
        }
    }

    /** Spec [version] placed on the current notebook, without changing it (no undo entries). */
    fun placeVersion(
        version: Int,
        markdown: String,
        layout: SpecLayoutModel,
        measure: TextMeasure,
    ): Placement = RunPlacer.apply(_notebook.value, version, markdown, layout, measure, geometry(), undo = null)

    /** Places spec [version] on the notebook; each row shift of the selected page is one undoable step. */
    fun applyVersion(
        version: Int,
        markdown: String,
        layout: SpecLayoutModel,
        measure: TextMeasure,
    ): Placement {
        var placement: Placement? = null
        update { n -> RunPlacer.apply(n, version, markdown, layout, measure, geometry(), undoStack).also { placement = it }.notebook }
        return checkNotNull(placement)
    }

    private fun geometry() = _notebook.value.canvas.let { NotebookGeometry(it.w, it.h) }

    /**
     * Shows the notebook of conversation [localId] (T-46 OQ1 A: one notebook per conversation): the current one is
     * saved first, then the bound one is loaded, or an empty one for a conversation without a notebook. A notebook not
     * yet bound to any conversation is adopted by the first one instead (what was drawn before belongs to it). The
     * undo history starts empty.
     */
    suspend fun bindConversation(localId: String) =
        bindLock.withLock {
            val shelf = checkNotNull(files) { "no notebook files" }
            if (_notebook.value.conversationLocalId == localId) return@withLock
            val target = shelf.store(localId)
            val loaded = withContext(ioDispatcher) { target.load() }
            // Changes made while this suspends (a stroke, a send marking its pages and pending segments) belong to the
            // shown notebook: save until no change landed during the save, then swap with no suspension in between.
            saveJob?.cancel()
            do {
                val saved = _notebook.value
                persist()
            } while (_notebook.value !== saved)
            swapTo(shelf, target, loaded, localId)
        }

    private suspend fun swapTo(
        shelf: NotebookFiles,
        target: NotebookStore,
        loaded: Notebook?,
        localId: String,
    ) {
        val now = _notebook.value
        val adopted = loaded == null && now.conversationLocalId == null
        val next = loaded ?: if (adopted) now else Notebook(defaultCanvas, listOf(Page(emptyList(), false)), 0)
        store = target
        undoStack = UndoStack()
        _preview.value = null
        _notebook.value = next.copy(conversationLocalId = localId)
        _canUndo.value = false
        _canRedo.value = false
        persist()
        if (adopted) withContext(ioDispatcher) { shelf.store(null).delete() }
    }

    /**
     * Conversation [localId] was deleted or pruned (its notebook file is gone): when the board shows it, the board
     * drops it without saving (a save would recreate the file) and shows an empty unbound notebook, which the next
     * selected conversation adopts.
     */
    suspend fun conversationGone(localId: String) =
        bindLock.withLock {
            if (_notebook.value.conversationLocalId == localId) reset()
        }

    /** Writes the notebook now, cancelling a pending debounced save. */
    suspend fun flush() {
        saveJob?.cancel()
        persist()
    }

    /** Saves the current notebook; an IO failure is surfaced in [saveError] and the in-memory notebook is kept. */
    private suspend fun persist() {
        val n = _notebook.value
        try {
            withContext(ioDispatcher) { store.save(n) }
            _saveError.value = null
        } catch (e: IOException) {
            _saveError.value = e.message ?: e.javaClass.simpleName
        }
    }

    /** Drops the notebook and its undo history (after Unpair deleted the saved file); nothing is written. */
    fun reset() {
        saveJob?.cancel()
        files?.let { store = it.store(null) }
        undoStack = UndoStack()
        _notebook.value = Notebook(defaultCanvas, listOf(Page(emptyList(), false)), 0)
        _canUndo.value = false
        _canRedo.value = false
    }

    private fun update(change: (Notebook) -> Notebook) {
        val before = _notebook.value
        val after = change(before)
        _canUndo.value = undoStack.canUndo(after)
        _canRedo.value = undoStack.canRedo(after)
        if (after == before) return
        if (after.current != before.current) _preview.value = null
        _notebook.value = after
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                persist()
            }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 500L
        const val DEFAULT_WIDTH = 4f

        /** The one stroke width of rectangle and arrow, whatever the pen width slider says. */
        const val SHAPE_WIDTH = 4f
        val DEFAULT_CANVAS = Canvas(1404, 1872, 300f)
    }
}
