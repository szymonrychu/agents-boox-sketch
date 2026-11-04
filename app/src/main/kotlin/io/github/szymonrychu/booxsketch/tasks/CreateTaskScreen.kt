package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val TAG_CREATE_TITLE = "create_title"
const val TAG_CREATE_BODY = "create_body"
const val TAG_CREATE_SUBMIT = "create_submit"
const val TAG_CREATE_CANCEL = "create_cancel"
const val TAG_CREATE_ERROR = "create_error"
const val TAG_CREATE_TITLE_ERROR = "create_title_error"
const val TAG_CREATE_LABEL_FIELD = "create_label_field"
const val TAG_CREATE_LABEL_ADD = "create_label_add"
const val TAG_CREATE_KIND_TASK = "create_kind_task"
const val TAG_CREATE_KIND_IDEA = "create_kind_idea"

fun createLabelTag(label: String) = "create_label_$label"

/** Full-screen create form: kind, title (max 200), description, labels. */
@Composable
fun CreateTaskScreen(
    vm: CreateTaskViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("New task", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Segment("Task", state.kind == "task", { vm.setKind("task") }, Modifier.testTag(TAG_CREATE_KIND_TASK))
            Segment("Idea", state.kind == "idea", { vm.setKind("idea") }, Modifier.testTag(TAG_CREATE_KIND_IDEA))
        }
        OutlinedTextField(
            value = state.title,
            onValueChange = vm::setTitle,
            label = { Text("Title") },
            singleLine = true,
            isError = state.titleError != null,
            modifier = Modifier.fillMaxWidth().testTag(TAG_CREATE_TITLE),
        )
        state.titleError?.let { Text(it, Modifier.testTag(TAG_CREATE_TITLE_ERROR)) }
        OutlinedTextField(
            value = state.body,
            onValueChange = vm::setBody,
            label = { Text("Description") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth().testTag(TAG_CREATE_BODY),
        )
        LabelsEditor(state, vm::addLabel, vm::removeLabel)
        state.error?.let { Text(it, Modifier.testTag(TAG_CREATE_ERROR)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.minTarget().testTag(TAG_CREATE_CANCEL)) { Text("Cancel") }
            Button(onClick = vm::submit, enabled = state.canSubmit, modifier = Modifier.minTarget().testTag(TAG_CREATE_SUBMIT)) {
                Text(if (state.submitting) "Creating" else "Create")
            }
        }
    }
}

@Composable
private fun LabelsEditor(
    state: CreateTaskState,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var labelText by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = labelText,
            onValueChange = { labelText = it.replace("\n", "") },
            label = { Text("Label") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(TAG_CREATE_LABEL_FIELD),
        )
        Button(
            onClick = {
                onAdd(labelText)
                labelText = ""
            },
            modifier = Modifier.minTarget().testTag(TAG_CREATE_LABEL_ADD),
        ) { Text("Add") }
    }
    state.labelError?.let { Text(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.labels.forEach {
            Chip(
                "$it  x",
                selected = true,
                onClick = { onRemove(it) },
                modifier = Modifier.testTag(createLabelTag(it)),
            )
        }
    }
}
