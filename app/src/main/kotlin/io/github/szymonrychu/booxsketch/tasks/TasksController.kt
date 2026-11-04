package io.github.szymonrychu.booxsketch.tasks

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.TasksClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch

/** Which screen of the Tasks tab is showing. */
sealed interface TasksScreen {
    data object List : TasksScreen

    data object Create : TasksScreen

    data class Detail(
        val key: String,
    ) : TasksScreen
}

/**
 * Navigation and view models of the Tasks tab, held by the app container so a tab switch keeps the filters, the page and
 * the open task. [events] is cold: it is only collected while the tab is on screen (Q10), never in the background.
 */
class TasksController(
    private val clientProvider: () -> TasksClient?,
    events: Flow<String>,
    private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit,
) {
    private val hints: Flow<String> = events.catch { if (it is ApiError.Unauthorized) onUnauthorized() }
    private val mutableScreen = MutableStateFlow<TasksScreen>(TasksScreen.List)
    val screen: StateFlow<TasksScreen> = mutableScreen.asStateFlow()

    private var listVm: TaskListViewModel? = null
    private var detailVm: TaskDetailViewModel? = null
    private var createVm: CreateTaskViewModel? = null

    /** The list view model, made on first use; null while the device is not paired. */
    fun list(): TaskListViewModel? =
        listVm ?: clientProvider()?.let { TaskListViewModel(it, hints, scope, onUnauthorized = onUnauthorized) }?.also { listVm = it }

    fun detail(key: String): TaskDetailViewModel? {
        detailVm?.takeIf { it.key == key }?.let { return it }
        return clientProvider()?.let { TaskDetailViewModel(it, key, hints, scope, onUnauthorized) }?.also { detailVm = it }
    }

    fun create(): CreateTaskViewModel? =
        createVm
            ?: clientProvider()
                ?.let { CreateTaskViewModel(it, scope, onCreated = ::created, onUnauthorized = onUnauthorized) }
                ?.also { createVm = it }

    fun open(key: String) {
        mutableScreen.value = TasksScreen.Detail(key)
    }

    fun startCreate() {
        createVm = null
        mutableScreen.value = TasksScreen.Create
    }

    /** Back from the detail or the create form to the list, which reloads in case something changed. */
    fun back() {
        detailVm = null
        createVm = null
        mutableScreen.value = TasksScreen.List
        listVm?.refresh()
    }

    /** A waiting session was tapped (Q8): the list filters by it and shows. */
    fun filterSession(sessionId: String) {
        detailVm = null
        listVm?.setSession(sessionId)
        mutableScreen.value = TasksScreen.List
    }

    /** Drops every view model and returns to the list, for example on unpair. */
    fun reset() {
        listVm = null
        detailVm = null
        createVm = null
        mutableScreen.value = TasksScreen.List
    }

    private fun created(key: String) {
        createVm = null
        listVm?.refresh()
        mutableScreen.value = TasksScreen.Detail(key)
    }
}
