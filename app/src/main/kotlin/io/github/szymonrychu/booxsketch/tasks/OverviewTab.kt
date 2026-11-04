package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.TaskDto

const val TAG_OVERVIEW_EDIT = "overview_edit"
const val TAG_OVERVIEW_BODY = "overview_body"
const val TAG_EDITOR_FIELD = "editor_field"
const val TAG_EDITOR_SAVE = "editor_save"
const val TAG_EDITOR_CANCEL = "editor_cancel"
const val TAG_OVERVIEW_LABEL_FIELD = "overview_label_field"
const val TAG_OVERVIEW_LABEL_ADD = "overview_label_add"
const val TAG_OVERVIEW_LABEL_ERROR = "overview_label_error"

fun overviewLabelTag(label: String) = "overview_label_$label"

fun waitingSessionTag(sessionId: String) = "waiting_$sessionId"

/** Description, labels and waiting sessions. Status, title and assignee are read only (Q9). */
@Composable
internal fun OverviewTab(
    task: TaskDto,
    state: TaskDetailState,
    vm: TaskDetailViewModel,
    onFilterSession: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Description", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = vm::startEditBody, modifier = Modifier.minTarget().testTag(TAG_OVERVIEW_EDIT)) { Text("Edit") }
        }
        Text(task.body.ifBlank { "No description" }, Modifier.testTag(TAG_OVERVIEW_BODY))
        Text("Labels", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            task.labels.forEach {
                Chip(
                    "$it  x",
                    selected = true,
                    onClick = { vm.removeLabel(it) },
                    modifier = Modifier.testTag(overviewLabelTag(it)),
                )
            }
        }
        LabelAdder(state.labelError, state.busy, vm::addLabel)
        if (task.waitingSessions.isNotEmpty()) {
            Text("Waiting for an answer", style = MaterialTheme.typography.titleMedium)
            task.waitingSessions.forEach { w ->
                TextButton(
                    onClick = { onFilterSession(w.sessionId) },
                    modifier = Modifier.fillMaxWidth().minTarget().testTag(waitingSessionTag(w.sessionId)),
                ) { Text(w.slug ?: w.sessionId, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun LabelAdder(
    error: String?,
    busy: Boolean,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.replace("\n", "") },
            label = { Text("Add label") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(TAG_OVERVIEW_LABEL_FIELD),
        )
        Button(
            onClick = {
                onAdd(text)
                text = ""
            },
            enabled = !busy,
            modifier = Modifier.minTarget().testTag(TAG_OVERVIEW_LABEL_ADD),
        ) { Text("Add") }
    }
    error?.let { Text(it, Modifier.testTag(TAG_OVERVIEW_LABEL_ERROR)) }
}

/** Full-screen description editor. */
@Composable
internal fun BodyEditor(
    initial: String,
    busy: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    Column(modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Edit description", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            minLines = 8,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(TAG_EDITOR_FIELD),
        )
        error?.let { Text(it, Modifier.testTag(TAG_DETAIL_ERROR)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.minTarget().testTag(TAG_EDITOR_CANCEL)) { Text("Cancel") }
            Button(onClick = { onSave(text) }, enabled = !busy, modifier = Modifier.minTarget().testTag(TAG_EDITOR_SAVE)) { Text("Save") }
        }
    }
}
