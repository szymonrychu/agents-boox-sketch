package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.onyx.UiRefresher
import io.github.szymonrychu.booxsketch.ui.BoardChrome
import kotlinx.coroutines.launch

/**
 * Full-screen board with the toolbar dock overlaid at the bottom. [chrome] carries the shell state
 * (dock visibility, tab bar rects, stroke feedback); null, as in previews and tests, keeps the dock
 * always visible. [setEraser] mirrors the eraser toggle into inputs that need it.
 * [overlays] are window-coordinate rects of anything drawn over the board; they and the toolbar
 * are excluded from raw pen input. For inputs that freeze the e-ink panel, raw drawing starts only
 * after the first frame and toolbar changes are refreshed through a [UiRefresher].
 */
@Suppress("LongParameterList")
@Composable
fun BoardScreen(
    vm: BoardViewModel,
    input: StrokeInput,
    modifier: Modifier = Modifier,
    setEraser: (Boolean) -> Unit = {},
    onSend: (all: Boolean, caption: String?) -> Unit = { _, _ -> },
    overlays: List<Rect> = emptyList(),
    chrome: BoardChrome? = null,
    background: Bitmap? = null,
    toolbar: ToolbarOptions = ToolbarOptions(),
) = BoardScreenWith(vm, input, modifier, setEraser, onSend, overlays, chrome, background = background, toolbarOptions = toolbar)

/** Arguments of the dock slot of [BoardScreenWith]; the default slot is [ToolbarDock]. */
internal class DockParams(
    val visible: Boolean,
    val onShow: () -> Unit,
    val onHide: () -> Unit,
    val ui: UiRefresh,
    val onExcludeChange: (List<Rect>) -> Unit,
    val rememberedDockSize: IntSize?,
    val onDockSize: (IntSize) -> Unit,
    val toolbar: @Composable () -> Unit,
)

internal typealias DockContent = @Composable BoxScope.(DockParams) -> Unit

private val DefaultDock: DockContent = { p ->
    ToolbarDock(p.visible, p.onShow, p.onHide, p.ui, p.onExcludeChange, p.rememberedDockSize, p.onDockSize, p.toolbar)
}

/** [BoardScreen] with the dock replaceable, so tests can drive the dock's exclude reports. */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun BoardScreenWith(
    vm: BoardViewModel,
    input: StrokeInput,
    modifier: Modifier = Modifier,
    setEraser: (Boolean) -> Unit = {},
    onSend: (all: Boolean, caption: String?) -> Unit = { _, _ -> },
    overlays: List<Rect> = emptyList(),
    chrome: BoardChrome? = null,
    dock: DockContent = DefaultDock,
    background: Bitmap? = null,
    toolbarOptions: ToolbarOptions = ToolbarOptions(),
) {
    val notebook by vm.notebook.collectAsState()
    val tool by vm.tool.collectAsState()
    val width by vm.width.collectAsState()
    val eraser by vm.eraser.collectAsState()
    val color by vm.color.collectAsState()
    val preview by vm.preview.collectAsState()
    LaunchedEffect(tool, width) { input.setTool(tool, width) }
    LaunchedEffect(color) { input.setColor(color) }
    LaunchedEffect(eraser) { setEraser(eraser) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { vm.flush() } }
    val view = LocalView.current
    // Created during composition so raw drawing is held off before the board first starts it.
    val ui = remember(input, view) { UiRefresher.forView(view, input) }
    // A dock show or hide tap holds raw drawing (on top of the refresher's hold) until the input runs with the new rects.
    val holds = remember(input) { ChromeHolds(input) }
    // Stop the input first: closing the refresher releases its holds, which must not re-enable a session about to close.
    DisposableEffect(input, ui) {
        onDispose {
            input.stop()
            (ui as? UiRefresher)?.close()
            holds.release()
        }
    }
    val dockVisible = chrome?.visible ?: true

    fun changeDock(
        target: Boolean,
        apply: () -> Unit,
    ) {
        // Without a chrome nothing changes, so no dock report would ever settle a hold.
        if (chrome == null) apply() else holds.change(DOCK_KEY, target, apply = apply)
    }
    // Auto-hide goes through the same hold and full refresh as a tap; it is refused while a stroke is in progress
    // (the hold restarts the input and would drop that stroke), and the shell retries after the next quiet gap.
    DisposableEffect(chrome, input, ui) {
        chrome?.autoHideHandler = {
            if (input.strokeInProgress) {
                false
            } else {
                if (chrome.visible) {
                    ui.chromeChanged()
                    changeDock(false) { chrome.hide() }
                }
                true
            }
        }
        onDispose { chrome?.autoHideHandler = null }
    }
    val exclude = holds.rects + (chrome?.overlays ?: emptyList()) + overlays
    Box(modifier.fillMaxSize()) {
        BoardView(
            notebook = notebook,
            input = input,
            onStroke = {
                vm.onStroke(it)
                chrome?.onStrokeCommitted()
            },
            onErase = vm::onErase,
            modifier = Modifier.fillMaxSize(),
            exclude = exclude,
            preview = preview,
            onShapeDrag = vm::onShapeDrag,
            onShapeCancel = vm::onShapeCancel,
            background = background,
        )
        // The board ignores system bars; the dock keeps clear of them.
        val dockArea = Modifier.fillMaxSize()
        Box(if (chrome == null) dockArea else dockArea.windowInsetsPadding(chrome.insets.only(DOCK_INSET_SIDES))) {
            dock(
                DockParams(
                    visible = dockVisible,
                    onShow = { changeDock(true) { chrome?.show() } },
                    onHide = { changeDock(false) { chrome?.hide() } },
                    ui = ui,
                    onExcludeChange = { holds.report(DOCK_KEY, dockVisible, it) },
                    rememberedDockSize = chrome?.dockSize,
                    onDockSize = { chrome?.dockSize = it },
                    toolbar = { BoardToolbar(vm, onSend = onSend, ui = ui, options = toolbarOptions) },
                ),
            )
        }
    }
    // Settled: this composition hands BoardView the exclude list of every overlay state that was asked for.
    val settled = holds.settled
    LaunchedEffect(settled, exclude) {
        if (settled) {
            // One frame on, BoardView has restarted the input with that list; only then lift the hold.
            withFrameNanos { }
            holds.finishIfSettled()
        }
    }
}

private val DOCK_INSET_SIDES = WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal

private const val DOCK_KEY = "dock"

/** [overlaySettled] for the dock with a change pending to [awaiting] (null: no change pending); the dock always needs rects. */
internal fun dockSettled(
    awaiting: Boolean?,
    visible: Boolean,
    rects: List<Rect>,
): Boolean = awaiting != null && overlaySettled(awaiting, needsRects = true, visible = visible, rects = rects)
