package io.github.szymonrychu.booxsketch.tasks

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.TaskDto
import io.github.szymonrychu.booxsketch.api.TaskEvents
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

data class TaskDetailState(
    val task: TaskDto? = null,
    val notFound: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val staleBanner: Boolean = false,
    val error: String? = null,
    val labelError: String? = null,
    val editingBody: Boolean = false,
)

/**
 * One task: the loaded JSON plus the edits the web allows (description, labels; more in the other tabs). Every write
 * runs one at a time; a 412 reloads the task and shows [STALE_TEXT]; a 404 shows the not-found state.
 */
@Suppress("TooManyFunctions") // one entry point per user action
class TaskDetailViewModel(
    internal val client: TasksClient,
    val key: String,
    private val events: Flow<String>,
    private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit = {},
    private val coalesceMs: Long = TaskListViewModel.COALESCE_MS,
) {
    private val mutable = MutableStateFlow(TaskDetailState())
    val state: StateFlow<TaskDetailState> = mutable.asStateFlow()

    init {
        reload()
    }

    /** Loads the task again; the stale banner and errors clear when it succeeds. */
    fun reload() {
        scope.launch { load(keepBanner = false) }
    }

    fun startEditBody() = mutable.update { it.copy(editingBody = true) }

    fun cancelEditBody() = mutable.update { it.copy(editingBody = false) }

    fun saveBody(text: String) {
        val task = mutable.value.task ?: return
        write { client.patchBody(key, task.version, text) }
    }

    fun addLabel(raw: String) {
        val task = mutable.value.task ?: return
        when (val result = TaskLabels.add(task.labels, raw)) {
            is LabelResult.Added -> write { client.patchLabels(key, task.version, result.labels) }
            LabelResult.Duplicate -> mutable.update { it.copy(labelError = null) }
            is LabelResult.Invalid -> mutable.update { it.copy(labelError = result.reason) }
            LabelResult.TooMany -> mutable.update { it.copy(labelError = "At most ${TaskLabels.MAX_LABELS} labels") }
        }
    }

    fun removeLabel(label: String) {
        val task = mutable.value.task ?: return
        if (label !in task.labels) return
        write { client.patchLabels(key, task.version, task.labels - label) }
    }

    /** Runs [call] as a write that answers with the whole task; ignored while another write runs. */
    internal fun write(call: suspend () -> TaskDto) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, labelError = null, staleBanner = false) }
        scope.launch { guarded { adopt(call()) } }
    }

    /** Runs [call] under the shared error handling (stale, not found, 401, others);. */
    internal suspend fun guarded(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            fail(e)
        }
    }

    internal fun adopt(task: TaskDto) =
        mutable.update {
            it.copy(task = task, busy = false, editingBody = false, error = null, notFound = false)
        }

    /** Collects change hints while the caller is active; a hint for this task (or a lost stream) reloads it once per window. */
    suspend fun observe() =
        coroutineScope {
            var pending: Job? = null
            events.collect { hint ->
                if ((hint == key || hint == TaskEvents.REFRESH) && pending?.isActive != true) {
                    pending =
                        launch {
                            delay(coalesceMs)
                            load(keepBanner = true, quiet = true)
                        }
                }
            }
        }

    private suspend fun load(
        keepBanner: Boolean,
        quiet: Boolean = false,
    ) {
        if (!quiet) mutable.update { it.copy(loading = it.task == null) }
        try {
            val task = client.get(key)
            mutable.update {
                it.copy(
                    task = task,
                    notFound = false,
                    loading = false,
                    busy = false,
                    error = null,
                    staleBanner = it.staleBanner && keepBanner,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            fail(e)
        }
    }

    private fun fail(e: ApiError) {
        when (e) {
            ApiError.Unauthorized -> {
                onUnauthorized()
            }

            is ApiError.NotFound -> {
                mutable.update { it.copy(notFound = true, loading = false, busy = false) }
            }

            ApiError.StaleVersion -> {
                mutable.update { it.copy(busy = false, staleBanner = true, editingBody = false) }
                scope.launch { load(keepBanner = true, quiet = true) }
                return
            }

            else -> {
                Unit
            }
        }
        mutable.update { it.copy(loading = false, busy = false, error = e.userText()) }
    }
}
