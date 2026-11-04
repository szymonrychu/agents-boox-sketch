package io.github.szymonrychu.booxsketch.documents

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.App
import kotlinx.coroutines.delay

const val DOCUMENTS_TAB_ID = "documents"
const val DOCUMENTS_TAB_ORDER = 20
const val TAG_DOCUMENTS_PREV = "documents_prev"
const val TAG_DOCUMENTS_NEXT = "documents_next"
const val TAG_DOCUMENTS_EMPTY = "documents_empty"
const val TAG_DOCUMENTS_LOADING = "documents_loading"
private const val SYNC_INTERVAL_MS = 20_000L

fun documentRowTag(localId: String) = "document_$localId"

/** The Documents tab: refreshes the conversations (which syncs the spec versions) on open and every 20 s while shown. */
@Composable
fun DocumentsTab(modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as App).container
    val page by container.documentsViewModel.page.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            container.conversations.refresh()
            delay(SYNC_INTERVAL_MS)
        }
    }
    DocumentsScreen(
        page = page,
        onOpen = container.documentsViewModel::open,
        onPrev = container.documentsViewModel::prev,
        onNext = container.documentsViewModel::next,
        modifier = modifier,
    )
}

@Composable
fun DocumentsScreen(
    page: DocumentsPage,
    onOpen: (DocumentRow) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    dateText: (String) -> String = { it.take(DATE_CHARS).replace('T', ' ') },
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Documents", style = MaterialTheme.typography.titleLarge)
        when {
            !page.loaded -> Text("Loading", Modifier.testTag(TAG_DOCUMENTS_LOADING))
            page.rows.isEmpty() -> Text("No documents yet", Modifier.testTag(TAG_DOCUMENTS_EMPTY))
            else -> page.rows.forEach { DocumentRowItem(it, dateText, onOpen) }
        }
        if (page.hasPrev || page.hasNext) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onPrev,
                    enabled = page.hasPrev,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOCUMENTS_PREV),
                ) { Text("Prev") }
                Text("${page.index + 1}")
                Button(
                    onClick = onNext,
                    enabled = page.hasNext,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOCUMENTS_NEXT),
                ) { Text("Next") }
            }
        }
    }
}

@Composable
private fun DocumentRowItem(
    row: DocumentRow,
    dateText: (String) -> String,
    onOpen: (DocumentRow) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(BorderStroke(1.dp, Color.Black))
            .clickable { onOpen(row) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(documentRowTag(row.conversationLocalId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title)
            Text("v${row.latestVersion}  ${dateText(row.latestCreated)}", style = MaterialTheme.typography.bodySmall)
        }
        when (row.state) {
            DocState.TRANSLATING -> Text("Translating")
            DocState.FAILED -> Text("Failed")
            DocState.IDLE -> Unit
        }
    }
}

private const val DATE_CHARS = 16
