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
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FEEDBACK_REFRESH_MS = 20_000L

/** Shows the document [row] full screen until Close; owns its view model. [input] is the pen input shared with the board. */
@Suppress("LongMethod")
@Composable
fun DocumentHost(
    container: AppContainer,
    row: DocumentRow,
    input: StrokeInput,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = remember(row.conversationLocalId) { container.documentViewModel(row) }
    val state by vm.state.collectAsState()
    val versions by vm.versions.collectAsState()
    val feedback by vm.feedback.collectAsState()
    // The refresh syncs specs and feedback: on open, then every 20 s so new translator notes show up.
    LaunchedEffect(row.conversationLocalId) {
        while (true) {
            container.conversations.refresh()
            delay(FEEDBACK_REFRESH_MS)
        }
    }
    var showSources by remember { mutableStateOf(false) }
    if (showSources) {
        val sourcesVm = remember(row.serverId, state.version) { container.sourcesViewModel(row.serverId, state.version) }
        val items by sourcesVm.items.collectAsState()
        SourcesScreen(items, onClose = { showSources = false })
        return
    }
    val turner = remember(vm, input) { PageTurner(input, { delta -> if (delta > 0) vm.next() else vm.prev() }, ::awaitFrames) }
    val inkable = !state.readOnly
    DocumentScreen(
        title = row.title,
        state = state,
        versions = versions,
        feedback = feedback,
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
        onPrev = { if (inkable) scope.launch { turner.turn(-1) } else vm.prev() },
        onNext = { if (inkable) scope.launch { turner.turn(1) } else vm.next() },
        pageArea = { modifier ->
            DocumentPageArea(
                conversationLocalId = row.conversationLocalId,
                state = state,
                layout = container.documentLayout,
                ink = container.documentInk,
                input = input,
                turner = turner,
                onFingerSwipe = { container.inputSelector.onFingerSwipe = it },
                onSendNotes = { caption -> container.onSendNotes(vm, caption) },
                modifier = modifier,
                onReadOnlySwipe = { dir ->
                    if (dir == SwipeDir.NEXT) {
                        vm.next()
                    } else if (dir == SwipeDir.PREV) {
                        vm.prev()
                    }
                },
            )
        },
    )
}
