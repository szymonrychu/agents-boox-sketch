package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.board.BoardViewModel
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The ink of documents: one [Notebook] per (conversation, version) whose pages are the frozen document pages and whose
 * canvas is the layout size, autosaved through the version's `NotebookStore`. A board view model is reused from the
 * board (same commands, undo, autosave); a version never shares ink with another, so a new spec version leaves the old
 * draft untouched.
 */
class DocumentInk(
    private val store: AnnotationStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val boards = HashMap<Pair<String, Int>, BoardViewModel>()

    /** The board of one version; its notebook is created with [pageCount] empty pages when none (or a mismatching one) is saved. */
    @Synchronized
    fun board(
        conversationLocalId: String,
        version: Int,
        pageCount: Int,
        canvas: Canvas,
    ): BoardViewModel =
        boards.getOrPut(conversationLocalId to version) {
            val notebookStore = store.notebookStore(conversationLocalId, version)
            val saved = notebookStore.load()
            if (saved == null || saved.pages.size != pageCount) {
                notebookStore.save(Notebook(canvas, List(pageCount) { Page(emptyList(), false) }, 0))
            }
            BoardViewModel(notebookStore, scope, io, canvas)
        }

    /** The saved ink of a version for read-only viewing, without starting any board. */
    fun saved(
        conversationLocalId: String,
        version: Int,
    ): Notebook? = boards[conversationLocalId to version]?.notebook?.value ?: store.notebookStore(conversationLocalId, version).load()

    /** Forgets the cached boards of a conversation (it was deleted, or the app was unpaired). */
    @Synchronized
    fun forget(conversationLocalId: String? = null) {
        boards.keys.removeAll { conversationLocalId == null || it.first == conversationLocalId }
    }
}

/**
 * A page turn on an inkable page: raw drawing is held off while the page and its background are swapped and drawn, and
 * only then released, so the new exclude rects and the new background are in place when the pen can draw again.
 */
class PageTurner(
    private val input: StrokeInput,
    private val turn: (delta: Int) -> Unit,
    private val awaitApplied: suspend () -> Unit,
) {
    suspend fun turn(delta: Int) {
        input.holdUi()
        try {
            turn.invoke(delta)
            awaitApplied()
        } finally {
            input.releaseUi()
        }
    }
}
