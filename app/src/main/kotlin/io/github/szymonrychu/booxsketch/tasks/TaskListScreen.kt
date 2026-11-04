@file:Suppress("TooManyFunctions") // one tag helper per tested node

package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.TaskDto

const val TAG_TASKS_PREV_PREFIX = "tasks_page"
const val TAG_TASKS_PREV = "tasks_page_prev"
const val TAG_TASKS_NEXT = "tasks_page_next"
const val TAG_TASKS_EMPTY = "tasks_empty"
const val TAG_TASKS_LOADING = "tasks_loading"
const val TAG_TASKS_ERROR = "tasks_error"
const val TAG_TASKS_NEW = "tasks_new"
const val TAG_TASKS_SESSION_FIELD = "tasks_session_field"
const val TAG_TASKS_SESSION_APPLY = "tasks_session_apply"
const val TAG_TASKS_LABEL_FIELD = "tasks_label_field"
const val TAG_TASKS_LABEL_ADD = "tasks_label_add"
const val TAG_TASKS_LABEL_ERROR = "tasks_label_error"

fun taskStatusFilterTag(filter: StatusFilter) = "tasks_status_${filter.wire}"

fun taskRowTag(key: String) = "task_$key"

fun taskStatusTag(key: String) = "task_status_$key"

fun taskBlockedTag(key: String) = "task_blocked_$key"

fun taskAssigneeTag(key: String) = "task_assignee_$key"

fun taskRowLabelTag(
    key: String,
    label: String,
) = "task_label_${key}_$label"

fun taskFilterLabelTag(label: String) = "tasks_filter_label_$label"

/** The status chip text of a task: its status with underscores spaced. */
internal fun statusText(status: String): String = status.replace('_', ' ')

/** The Tasks list: filters above, [state].items as 48 dp rows, Prev/Next below. Stateless; [TaskListScreen] feeds it. */
@Suppress("LongParameterList")
@Composable
fun TaskListContent(
    state: TaskListState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onStatus: (StatusFilter) -> Unit,
    onSession: (String) -> Unit,
    onAddLabel: (String) -> Unit,
    onRemoveLabel: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onCreate, modifier = Modifier.minTarget().testTag(TAG_TASKS_NEW)) { Text("New task") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusFilter.entries.forEach { f ->
                Segment(f.label, f == state.status, { onStatus(f) }, Modifier.testTag(taskStatusFilterTag(f)))
            }
        }
        SessionFilter(state.session, onSession)
        LabelFilter(state, onAddLabel, onRemoveLabel)
        state.error?.let { Text(it, Modifier.testTag(TAG_TASKS_ERROR)) }
        when {
            state.loading && state.items.isEmpty() -> Text("Loading", Modifier.testTag(TAG_TASKS_LOADING))
            state.items.isEmpty() && state.error == null -> Text("No tasks match", Modifier.testTag(TAG_TASKS_EMPTY))
            else -> state.items.forEach { TaskRow(it, onOpen, onAddLabel, onSession) }
        }
        Pager(state.pageIndex, state.pageIndex > 0, state.hasNext, onPrev, onNext, TAG_TASKS_PREV_PREFIX)
    }
}

@Composable
private fun SessionFilter(
    current: String,
    onSession: (String) -> Unit,
) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.replace("\n", "") },
            label = { Text("Session") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSession(text) }),
            modifier = Modifier.weight(1f).testTag(TAG_TASKS_SESSION_FIELD),
        )
        Button(onClick = { onSession(text) }, modifier = Modifier.minTarget().testTag(TAG_TASKS_SESSION_APPLY)) { Text("Filter") }
        if (current.isNotEmpty()) {
            TextButton(onClick = { onSession("") }, modifier = Modifier.minTarget()) { Text("Clear") }
        }
    }
}

@Composable
private fun LabelFilter(
    state: TaskListState,
    onAddLabel: (String) -> Unit,
    onRemoveLabel: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.replace("\n", "") },
            label = { Text("Label") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAddLabel(text) }),
            modifier = Modifier.weight(1f).testTag(TAG_TASKS_LABEL_FIELD),
        )
        Button(
            onClick = {
                onAddLabel(text)
                text = ""
            },
            modifier = Modifier.minTarget().testTag(TAG_TASKS_LABEL_ADD),
        ) { Text("Add") }
    }
    state.labelError?.let { Text(it, Modifier.testTag(TAG_TASKS_LABEL_ERROR)) }
    if (state.labels.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.labels.forEach { label ->
                Chip(
                    "$label  x",
                    selected = true,
                    onClick = { onRemoveLabel(label) },
                    modifier = Modifier.testTag(taskFilterLabelTag(label)),
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskDto,
    onOpen: (String) -> Unit,
    onAddLabel: (String) -> Unit,
    onSession: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .minTarget()
                .clickable { onOpen(task.key) }
                .testTag(taskRowTag(task.key)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(task.key, fontWeight = FontWeight.Bold)
            Text(task.title, Modifier.weight(1f))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(statusText(task.status), Modifier.testTag(taskStatusTag(task.key)))
            if (task.openDecisions > 0) Chip("Blocked (${task.openDecisions})", Modifier.testTag(taskBlockedTag(task.key)))
            task.labels.forEach { label ->
                Chip(label, onClick = { onAddLabel(label) }, modifier = Modifier.testTag(taskRowLabelTag(task.key, label)))
            }
            task.assigneeSession?.let { id ->
                TextButton(onClick = { onSession(id) }, modifier = Modifier.minTarget().testTag(taskAssigneeTag(task.key))) { Text(id) }
            }
        }
    }
}

/** The Tasks list for [vm]; the stream hints refresh it while this is on screen. */
@Composable
fun TaskListScreen(
    vm: TaskListViewModel,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.observe() }
    RefreshOnChange(state.pageIndex, state.status)
    TaskListContent(
        state = state,
        onOpen = onOpen,
        onCreate = onCreate,
        onStatus = vm::setStatus,
        onSession = vm::setSession,
        onAddLabel = vm::addLabel,
        onRemoveLabel = vm::removeLabel,
        onPrev = vm::prev,
        onNext = vm::next,
        modifier = modifier,
    )
}
