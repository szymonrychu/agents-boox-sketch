package io.github.szymonrychu.booxsketch.tasks

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.TasksClient
import io.github.szymonrychu.booxsketch.model.LabelResult
import io.github.szymonrychu.booxsketch.model.TaskLabels
import io.github.szymonrychu.booxsketch.model.newClientKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_TITLE = 200

data class CreateTaskState(
    val kind: String = "task",
    val title: String = "",
    val body: String = "",
    val labels: List<String> = emptyList(),
    val labelError: String? = null,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val titleError: String? get() = if (title.length > MAX_TITLE) "Title is at most $MAX_TITLE characters" else null
    val canSubmit: Boolean get() = title.isNotBlank() && titleError == null && !submitting
}

/** One create form. Its client key is made once and reused by every retry, so a double tap or a retry never doubles the task. */
class CreateTaskViewModel(
    private val client: TasksClient,
    private val scope: CoroutineScope,
    private val onCreated: (String) -> Unit,
    private val onUnauthorized: () -> Unit = {},
    private val clientKey: String = newClientKey(),
) {
    private val mutable = MutableStateFlow(CreateTaskState())
    val state: StateFlow<CreateTaskState> = mutable.asStateFlow()

    fun setKind(kind: String) = mutable.update { it.copy(kind = kind) }

    fun setTitle(title: String) = mutable.update { it.copy(title = title.replace("\n", " ")) }

    fun setBody(body: String) = mutable.update { it.copy(body = body) }

    fun addLabel(raw: String) {
        when (val result = TaskLabels.add(mutable.value.labels, raw)) {
            is LabelResult.Added -> mutable.update { it.copy(labels = result.labels, labelError = null) }
            LabelResult.Duplicate -> mutable.update { it.copy(labelError = null) }
            is LabelResult.Invalid -> mutable.update { it.copy(labelError = result.reason) }
            LabelResult.TooMany -> mutable.update { it.copy(labelError = "At most ${TaskLabels.MAX_LABELS} labels") }
        }
    }

    fun removeLabel(label: String) = mutable.update { it.copy(labels = it.labels - label, labelError = null) }

    fun submit() {
        val s = mutable.value
        if (!s.canSubmit) return
        mutable.update { it.copy(submitting = true, error = null) }
        scope.launch {
            try {
                val task = client.create(clientKey, s.kind, s.title.trim(), s.body.ifBlank { null }, s.labels)
                mutable.update { it.copy(submitting = false) }
                onCreated(task.key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                fail(e)
            }
        }
    }

    private fun fail(e: ApiError) {
        if (e is ApiError.Unauthorized) onUnauthorized()
        mutable.update { it.copy(submitting = false, error = e.userText()) }
    }
}
