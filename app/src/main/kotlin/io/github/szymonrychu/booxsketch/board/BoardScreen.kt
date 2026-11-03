package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import kotlinx.coroutines.launch

/** Board above the toolbar. [setEraser] mirrors the eraser toggle into inputs that need it. */
@Composable
fun BoardScreen(
    vm: BoardViewModel,
    input: StrokeInput,
    modifier: Modifier = Modifier,
    setEraser: (Boolean) -> Unit = {},
    onSend: (all: Boolean, caption: String?) -> Unit = { _, _ -> },
) {
    val notebook by vm.notebook.collectAsState()
    val tool by vm.tool.collectAsState()
    val width by vm.width.collectAsState()
    val eraser by vm.eraser.collectAsState()
    LaunchedEffect(tool, width) { input.setTool(tool, width) }
    LaunchedEffect(eraser) { setEraser(eraser) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { vm.flush() } }
    DisposableEffect(input) { onDispose { input.stop() } }
    Column(modifier.fillMaxSize()) {
        BoardView(
            canvas = notebook.canvas,
            page = notebook.pages[notebook.current],
            input = input,
            onStroke = vm::onStroke,
            onErase = vm::onErase,
            modifier = Modifier.weight(1f),
        )
        BoardToolbar(vm, onSend = onSend)
    }
}
