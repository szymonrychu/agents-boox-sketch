package io.github.szymonrychu.booxsketch.board

import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Command
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.UndoStack
import io.github.szymonrychu.booxsketch.model.hitStrokes
import io.github.szymonrychu.booxsketch.model.snapShape
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Board state: the notebook, pen settings and per-page undo. Autosaves 500 ms after the last change. */
@Suppress("TooManyFunctions") // one small intent method per board action (tools, colours, pages, undo, send)
class BoardViewModel(
    private val store: NotebookStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultCanvas: Canvas = DEFAULT_CANVAS,
) {
    private var undoStack = UndoStack()
    private var saveJob: Job? = null

    private val _notebook = MutableStateFlow(store.load() ?: Notebook(defaultCanvas, listOf(Page(emptyList(), false)), 0))
    val notebook: StateFlow<Notebook> = _notebook.asStateFlow()

    private val _tool = MutableStateFlow(Tool.BALLPOINT)
    val tool: StateFlow<Tool> = _tool.asStateFlow()

    private val _width = MutableStateFlow(DEFAULT_WIDTH)
    val width: StateFlow<Float> = _width.asStateFlow()

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

    private val _saveError = MutableStateFlow<String?>(null)

    /** Message of the last failed save, null once a save succeeded again. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun setTool(tool: Tool) {
        _tool.value = tool
        _eraser.value = false
    }

    fun setColor(color: PenColor) {
        _color.value = color
        _eraser.value = false
    }

    fun setWidth(width: Float) {
        _width.value = width
    }

    fun setEraser(on: Boolean) {
        _eraser.value = on
    }

    private val currentStrokes: List<Stroke>
        get() = _notebook.value.let { it.pages[it.current].strokes }

    /** Shape tools: previews the snapped shape for a drag in canvas pixels (same call as the commit). */
    fun onShapeDrag(drag: Stroke) {
        _preview.value = snapShape(drag, currentStrokes)
    }

    fun onShapeCancel() {
        _preview.value = null
    }

    /** Commits a stroke; a shape-tool drag is snapped like its preview and dropped when too small. */
    fun onStroke(stroke: Stroke) {
        if (!stroke.tool.isShape) return update { undoStack.apply(it, Command.AddStroke(stroke)) }
        _preview.value = null
        val shape = snapShape(stroke, currentStrokes) ?: return
        update { undoStack.apply(it, Command.AddStroke(shape)) }
    }

    /** Erases the strokes of the current page hit by a circle at (x, y), in canvas pixels; undoable. */
    fun onErase(
        x: Float,
        y: Float,
        radius: Float,
    ) = update { n ->
        val ids = hitStrokes(n.pages[n.current].strokes, x, y, radius)
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
        val DEFAULT_CANVAS = Canvas(1404, 1872, 300f)
    }
}
