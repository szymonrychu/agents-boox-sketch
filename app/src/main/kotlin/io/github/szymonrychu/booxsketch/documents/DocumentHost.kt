package io.github.szymonrychu.booxsketch.documents

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.szymonrychu.booxsketch.AppContainer
import kotlinx.coroutines.launch

/** Shows the document [row] full screen until Close; owns its view model. */
@Composable
fun DocumentHost(
    container: AppContainer,
    row: DocumentRow,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = remember(row.conversationLocalId) { container.documentViewModel(row) }
    val state by vm.state.collectAsState()
    val versions by vm.versions.collectAsState()
    LaunchedEffect(row.conversationLocalId) { container.conversations.refresh() }
    var showSources by remember { mutableStateOf(false) }
    if (showSources) {
        val sourcesVm = remember(row.serverId, state.version) { container.sourcesViewModel(row.serverId, state.version) }
        val items by sourcesVm.items.collectAsState()
        SourcesScreen(items, onClose = { showSources = false })
        return
    }
    DocumentScreen(
        title = row.title,
        state = state,
        versions = versions,
        onClose = { container.openDocument.value = null },
        onSelectVersion = vm::selectVersion,
        onShare = {
            scope.launch {
                val markdown = vm.markdown() ?: return@launch
                val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, markdown)
                context.startActivity(Intent.createChooser(send, "Share spec").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        onSources = { showSources = true },
        onPrev = vm::prev,
        onNext = vm::next,
    )
}
