package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.Tool

internal const val TAG_SEND = "send"
internal const val TAG_PAGE_LABEL = "page_label"
internal const val SEND_ALL_LABEL = "Send all pages"
private const val MIN_WIDTH = 1f
private const val MAX_WIDTH = 24f

/**
 * Tools, width, eraser, undo, redo, clear, page strip, caption and Send. [onSend] gets
 * `all = false` on tap (changed pages only, D47 B) and `all = true` from the long-press
 * "Send all pages" item; the caption is null when blank. Every toolbar state change is reported to
 * [ui] so it reaches an e-ink panel frozen by raw drawing; width drags report unsettled changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoardToolbar(
    vm: BoardViewModel,
    modifier: Modifier = Modifier,
    onSend: (all: Boolean, caption: String?) -> Unit = { _, _ -> },
    ui: UiRefresh = UiRefresh.None,
) {
    val notebook by vm.notebook.collectAsState()
    val tool by vm.tool.collectAsState()
    val eraser by vm.eraser.collectAsState()
    var caption by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val pageCount = notebook.pages.size

    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in Tool.entries) {
                FilterChip(selected = !eraser && tool == t, onClick = ui.act { vm.setTool(t) }, label = { Text(t.wire) })
            }
            FilterChip(selected = eraser, onClick = ui.act { vm.setEraser(!eraser) }, label = { Text("Eraser") })
        }
        EditRow(vm, ui)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = ui.act { vm.selectPage(notebook.current - 1) }, enabled = notebook.current > 0) { Text("Previous") }
            Text("page ${notebook.current + 1} of $pageCount", Modifier.testTag(TAG_PAGE_LABEL))
            TextButton(
                onClick = ui.act { vm.selectPage(notebook.current + 1) },
                enabled = notebook.current < pageCount - 1,
            ) { Text("Next") }
            TextButton(onClick = ui.act(vm::addPage)) { Text("Add page") }
            TextButton(onClick = ui.act { vm.deletePage(notebook.current) }) { Text("Delete page") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = caption,
                onValueChange = {
                    caption = it
                    ui.uiChanged(settled = false)
                },
                label = { Text("Caption") },
                modifier = Modifier.weight(1f),
            )
            Box {
                SendButton(onTap = { onSend(false, caption.ifBlank { null }) }, onLongPress = ui.act { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = ui.act { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(SEND_ALL_LABEL) },
                        onClick =
                            ui.act {
                                menu = false
                                onSend(true, caption.ifBlank { null })
                            },
                    )
                }
            }
        }
    }
}

/** Runs [block], then reports a settled UI change. */
private fun UiRefresh.act(block: () -> Unit): () -> Unit =
    {
        block()
        uiChanged()
    }

/** Width slider (drags report unsettled changes, release a settled one), undo, redo and clear. */
@Composable
private fun EditRow(
    vm: BoardViewModel,
    ui: UiRefresh,
) {
    val width by vm.width.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Width")
        Slider(
            value = width,
            onValueChange = {
                vm.setWidth(it)
                ui.uiChanged(settled = false)
            },
            onValueChangeFinished = { ui.uiChanged() },
            valueRange = MIN_WIDTH..MAX_WIDTH,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = ui.act(vm::undo), enabled = canUndo) { Text("Undo") }
        TextButton(onClick = ui.act(vm::redo), enabled = canRedo) { Text("Redo") }
        TextButton(onClick = ui.act(vm::clear)) { Text("Clear") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    // A Button swallows long presses, so the click handling sits on a plain box styled as one.
    Box(
        Modifier
            .testTag(TAG_SEND)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text("Send", color = MaterialTheme.colorScheme.onPrimary)
    }
}
