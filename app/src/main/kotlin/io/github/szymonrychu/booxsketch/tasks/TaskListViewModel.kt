package io.github.szymonrychu.booxsketch.tasks

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.TaskDto
import io.github.szymonrychu.booxsketch.api.TasksClient
import io.github.szymonrychu.booxsketch.model.LabelResult
import io.github.szymonrychu.booxsketch.model.TaskLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The status filter of the list; [wire] is the `status` query value. */
enum class StatusFilter(
    val wire: String,
    val label: String,
) {
    ACTIVE("active", "Active"),
    DONE("done", "Done"),
    DROPPED("dropped", "Dropped"),
    ALL("all", "All"),
}

data class TaskListState(
    val items: List<TaskDto> = emptyList(),
    val status: StatusFilter = StatusFilter.ACTIVE,
    val session: String = "",
    val labels: List<String> = emptyList(),
    val pageIndex: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    /** Seconds the server asked to wait after a 429; automatic refreshes stay off until then. */
    val retryAfterS: Long? = null,
    val labelError: String? = null,
)

/**
 * The paged task list. The API cursor is forward-only, so the cursor of every visited page is kept in a stack. Live
 * change hints only mark the page dirty: one refresh per [coalesceMs] window, and state changes only when the JSON did.
 */
@Suppress("TooManyFunctions") // one entry point per filter and paging action
class TaskListViewModel(
    private val client: TasksClient,
    private val events: Flow<String>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val coalesceMs: Long = COALESCE_MS,
    private val onUnauthorized: () -> Unit = {},
) {
    private val mutable = MutableStateFlow(TaskListState())
    val state: StateFlow<TaskListState> = mutable.asStateFlow()

    private val cursors = mutableListOf<String?>(null)
    private var loadJob: Job? = null
    private var blockedUntilMs = 0L

    init {
        load(0, background = false)
    }

    fun setStatus(status: StatusFilter) {
        if (status == mutable.value.status) return
        mutable.update { it.copy(status = status) }
        restart()
    }

    /** Filters by the session slug or id; blank clears. The assignee and waiting-session taps call this (Q8). */
    fun setSession(session: String) {
        val trimmed = session.trim()
        if (trimmed == mutable.value.session) return
        mutable.update { it.copy(session = trimmed) }
        restart()
    }

    fun addLabel(raw: String) {
        when (val result = TaskLabels.add(mutable.value.labels, raw)) {
            is LabelResult.Added -> {
                mutable.update { it.copy(labels = result.labels, labelError = null) }
                restart()
            }

            LabelResult.Duplicate -> {
                mutable.update { it.copy(labelError = null) }
            }

            is LabelResult.Invalid -> {
                mutable.update { it.copy(labelError = result.reason) }
            }

            LabelResult.TooMany -> {
                mutable.update { it.copy(labelError = "At most ${TaskLabels.MAX_LABELS} labels") }
            }
        }
    }

    fun removeLabel(label: String) {
        if (label !in mutable.value.labels) return
        mutable.update { it.copy(labels = it.labels - label, labelError = null) }
        restart()
    }

    fun next() {
        val s = mutable.value
        if (s.hasNext) load(s.pageIndex + 1, background = false)
    }

    fun prev() {
        val s = mutable.value
        if (s.pageIndex > 0) load(s.pageIndex - 1, background = false)
    }

    /** Reloads the visible page. An [auto] refresh (a live hint) stays quiet and waits out a 429 first. */
    fun refresh(auto: Boolean = false) {
        if (auto && clock() < blockedUntilMs) return
        load(mutable.value.pageIndex, background = auto)
    }

    /** Collects the change hints while the caller is active (the tab is visible); cancel it to stop the stream. */
    suspend fun observe() =
        coroutineScope {
            var pending: Job? = null
            events.collect {
                if (pending?.isActive != true) {
                    pending =
                        launch {
                            delay(coalesceMs)
                            refresh(auto = true)
                        }
                }
            }
        }

    private fun restart() {
        cursors.clear()
        cursors.add(null)
        load(0, background = false)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun load(
        index: Int,
        background: Boolean,
    ) {
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val s = mutable.value
                if (!background) mutable.update { it.copy(loading = true, error = null, retryAfterS = null) }
                try {
                    val page = client.list(s.status.wire, s.session.ifBlank { null }, s.labels, cursors.getOrNull(index), PAGE_SIZE)
                    while (cursors.size > index + 1) cursors.removeAt(cursors.lastIndex)
                    page.nextCursor?.let { cursors.add(it) }
                    mutable.update {
                        it.copy(
                            items = page.items,
                            pageIndex = index,
                            hasNext = page.nextCursor != null,
                            loading = false,
                            error = null,
                            retryAfterS = null,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    fail(e)
                }
            }
    }

    private fun fail(e: ApiError) {
        if (e is ApiError.Unauthorized) onUnauthorized()
        val wait = (e as? ApiError.RateLimited)?.let { it.retryAfterS ?: DEFAULT_RETRY_AFTER_S }
        if (wait != null) blockedUntilMs = clock() + wait * MS_PER_S
        mutable.update { it.copy(loading = false, error = e.userText(), retryAfterS = wait) }
    }

    companion object {
        const val PAGE_SIZE = 10
        const val COALESCE_MS = 3_000L
        private const val MS_PER_S = 1_000L
    }
}
