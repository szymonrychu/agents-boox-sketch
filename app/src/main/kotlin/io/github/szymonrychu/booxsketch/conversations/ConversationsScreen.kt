package io.github.szymonrychu.booxsketch.conversations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.App
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val CONVERSATIONS_TAB_ID = "conversations"
const val TAG_NEW_CONVERSATION = "conversations_new"
const val TAG_CONVERSATIONS_ERROR = "conversations_error"
const val TAG_CONFIRM_DELETE = "conversations_confirm_delete"

fun conversationRowTag(localId: String) = "conversation_$localId"

fun conversationDeleteTag(localId: String) = "conversation_delete_$localId"

private val localDate: (Long) -> String = { ms ->
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

/**
 * The conversation list tab: server conversations merged with local ones, New conversation,
 * select (sends go to the selected one) and delete. State lives in the app container.
 */
@Composable
fun ConversationsTab(modifier: Modifier = Modifier) {
    val repo = (LocalContext.current.applicationContext as App).container.conversations
    val items by repo.conversations.collectAsState(initial = emptyList())
    val selected by repo.selected.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { error = repo.refresh() }
    ConversationsScreen(
        conversations = items,
        // No explicit selection: sends go to the most recent conversation, the top row.
        selected = selected ?: items.firstOrNull()?.localId,
        error = error,
        onNew = { scope.launch { repo.newConversation() } },
        onSelect = repo::select,
        onDelete = { id -> scope.launch { error = repo.delete(id) } },
        onRefresh = { scope.launch { error = repo.refresh() } },
        modifier = modifier,
    )
}

@Suppress("LongParameterList")
@Composable
fun ConversationsScreen(
    conversations: List<ConversationEntity>,
    selected: String?,
    error: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    dateText: (Long) -> String = localDate,
) {
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Refresh") }
            Button(
                onClick = onNew,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_NEW_CONVERSATION),
            ) { Text("New conversation") }
        }
        if (error != null) Text(error, Modifier.testTag(TAG_CONVERSATIONS_ERROR))
        if (conversations.isEmpty()) Text("No conversations yet. Send a sketch to start one.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.localId }) { c ->
                ConversationRow(c, c.localId == selected, dateText, { onSelect(c.localId) }, { pendingDelete = c })
            }
        }
    }
    pendingDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("\"${c.title}\" and its history are deleted on this device and on the server.") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    onDelete(c.localId)
                }, modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_CONFIRM_DELETE)) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    c: ConversationEntity,
    isSelected: Boolean,
    dateText: (Long) -> String,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .border(BorderStroke(if (isSelected) 3.dp else 1.dp, Color.Black))
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(conversationRowTag(c.localId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            val status = if (c.serverId == null) "not sent yet" else "started ${dateText(c.createdAtMs)}"
            Text(if (isSelected) "Selected - $status" else status, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(conversationDeleteTag(c.localId)),
        ) { Text("Delete") }
    }
}
