package io.github.szymonrychu.booxsketch.board

import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Command
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.UndoStack
import io.github.szymonrychu.booxsketch.model.hitStrokes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Board state: the notebook, pen settings and per-page undo. Autosaves 500 ms after the last change. */
class BoardViewModel(
    private val store: NotebookStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    defaultCanvas: Canvas = DEFAULT_CANVAS,
) {
    private val undoStack = UndoStack()
    private var saveJob: Job? = null

    private val _notebook = MutableStateFlow(store.load() ?: Notebook(defaultCanvas, listOf(Page(emptyList(), false)), 0))
    val notebook: StateFlow<Notebook> = _notebook.asStateFlow()

    private val _tool = MutableStateFlow(Tool.BALLPOINT)
    val tool: StateFlow<Tool> = _tool.asStateFlow()

    private val _width = MutableStateFlow(DEFAULT_WIDTH)
    val width: StateFlow<Float> = _width.asStateFlow()

    private val _eraser = MutableStateFlow(false)
    val eraser: StateFlow<Boolean> = _eraser.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun setTool(tool: Tool) {
        _tool.value = tool
        _eraser.value = false
    }

    fun setWidth(width: Float) {
        _width.value = width
    }

    fun setEraser(on: Boolean) {
        _eraser.value = on
    }

    fun onStroke(stroke: Stroke) = update { undoStack.apply(it, Command.AddStroke(stroke)) }

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

    /** Writes the notebook now, cancelling a pending debounced save. */
    suspend fun flush() {
        saveJob?.cancel()
        val n = _notebook.value
        withContext(ioDispatcher) { store.save(n) }
    }

    private fun update(change: (Notebook) -> Notebook) {
        val before = _notebook.value
        val after = change(before)
        _canUndo.value = undoStack.canUndo(after)
        _canRedo.value = undoStack.canRedo(after)
        if (after == before) return
        _notebook.value = after
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                val n = _notebook.value
                withContext(ioDispatcher) { store.save(n) }
            }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 500L
        const val DEFAULT_WIDTH = 4f
        val DEFAULT_CANVAS = Canvas(1404, 1872, 300f)
    }
}
