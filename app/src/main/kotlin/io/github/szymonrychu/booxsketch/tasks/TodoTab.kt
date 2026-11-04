package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.TaskDto
import io.github.szymonrychu.booxsketch.api.TodoItemDto

const val TAG_TODO_EMPTY = "todo_empty"
const val TAG_TODO_FIELD = "todo_field"
const val TAG_TODO_ADD = "todo_add"
const val TAG_TODO_ERROR = "todo_error"

fun todoTextTag(id: Long) = "todo_text_$id"

fun todoStatusTag(
    id: Long,
    status: String,
) = "todo_${id}_$status"

fun todoRemoveTag(id: Long) = "todo_remove_$id"

private val TODO_STATUSES = listOf("todo" to "To do", "inprogress" to "In progress", "done" to "Done")

/** The checklist: a three-state control per row, add and remove (whole-list PUT). No text edit or reorder (Q9). */
@Composable
internal fun TodoTab(
    task: TaskDto,
    state: TaskDetailState,
    vm: TaskDetailViewModel,
) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task.todo.isEmpty()) Text("No todo items", Modifier.testTag(TAG_TODO_EMPTY))
        task.todo.forEach { TodoRow(it, vm) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", "") },
                label = { Text("New item") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(TAG_TODO_FIELD),
            )
            Button(
                onClick = {
                    vm.addTodo(text)
                    text = ""
                },
                enabled = !state.busy,
                modifier = Modifier.minTarget().testTag(TAG_TODO_ADD),
            ) { Text("Add") }
        }
        state.todoError?.let { Text(it, Modifier.testTag(TAG_TODO_ERROR)) }
    }
}

@Composable
private fun TodoRow(
    item: TodoItemDto,
    vm: TaskDetailViewModel,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val done = item.status == "done"
        Text(
            if (done) "Done: ${item.text}" else item.text,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.testTag(todoTextTag(item.id)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TODO_STATUSES.forEach { (wire, label) ->
                Segment(label, item.status == wire, { vm.setTodoStatus(item.id, wire) }, Modifier.testTag(todoStatusTag(item.id, wire)))
            }
            TextButton(
                onClick = { vm.removeTodo(item.id) },
                modifier = Modifier.minTarget().testTag(todoRemoveTag(item.id)),
            ) { Text("Remove") }
        }
    }
}
