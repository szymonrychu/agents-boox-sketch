package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.onyx.UiRefresher
import kotlinx.coroutines.launch

/**
 * Board above the toolbar. [setEraser] mirrors the eraser toggle into inputs that need it.
 * [overlays] are window-coordinate rects of anything drawn over the board; they and the toolbar
 * are excluded from raw pen input. For inputs that freeze the e-ink panel, raw drawing starts only
 * after the first frame and toolbar changes are refreshed through a [UiRefresher].
 */
@Composable
fun BoardScreen(
    vm: BoardViewModel,
    input: StrokeInput,
    modifier: Modifier = Modifier,
    setEraser: (Boolean) -> Unit = {},
    onSend: (all: Boolean, caption: String?) -> Unit = { _, _ -> },
    overlays: List<Rect> = emptyList(),
) {
    val notebook by vm.notebook.collectAsState()
    val tool by vm.tool.collectAsState()
    val width by vm.width.collectAsState()
    val eraser by vm.eraser.collectAsState()
    LaunchedEffect(tool, width) { input.setTool(tool, width) }
    LaunchedEffect(eraser) { setEraser(eraser) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { vm.flush() } }
    val view = LocalView.current
    // Created during composition so raw drawing is held off before the board first starts it.
    val ui = remember(input, view) { UiRefresher.forView(view, input) }
    // Stop the input first: closing the refresher releases its holds, which must not re-enable a session about to close.
    DisposableEffect(input, ui) {
        onDispose {
            input.stop()
            (ui as? UiRefresher)?.close()
        }
    }
    var toolbar by remember { mutableStateOf<Rect?>(null) }
    Column(modifier.fillMaxSize()) {
        BoardView(
            notebook = notebook,
            input = input,
            onStroke = vm::onStroke,
            onErase = vm::onErase,
            modifier = Modifier.weight(1f),
            exclude = listOfNotNull(toolbar) + overlays,
        )
        Box(
            Modifier.onGloballyPositioned { c ->
                val b = c.boundsInWindow()
                toolbar = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            },
        ) {
            BoardToolbar(vm, onSend = onSend, ui = ui)
        }
    }
}
