package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.TaskDto

const val TAG_COMMENTS_EMPTY = "comments_empty"
const val TAG_COMMENT_FIELD = "comment_field"
const val TAG_COMMENT_ADD = "comment_add"

fun commentTag(id: Long) = "comment_$id"

/** "2026-11-04T10:03:00Z" as "2026-11-04 10:03". */
internal fun shortTime(iso: String): String = iso.take(TIME_CHARS).replace('T', ' ')

private const val TIME_CHARS = 16

/** The comments, oldest first, and an add field. */
@Composable
internal fun CommentsTab(
    task: TaskDto,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task.comments.isEmpty()) Text("No comments yet", Modifier.testTag(TAG_COMMENTS_EMPTY))
        task.comments.forEach { c ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Black)
                    .padding(8.dp)
                    .testTag(commentTag(c.id)),
            ) {
                Text("${c.authorSlug ?: c.authorKind}  ${shortTime(c.createdAt)}", style = MaterialTheme.typography.bodySmall)
                Text(c.body)
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Add a comment") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(TAG_COMMENT_FIELD),
        )
        Button(
            onClick = {
                onAdd(text)
                text = ""
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.minTarget().testTag(TAG_COMMENT_ADD),
        ) { Text("Add comment") }
    }
}
