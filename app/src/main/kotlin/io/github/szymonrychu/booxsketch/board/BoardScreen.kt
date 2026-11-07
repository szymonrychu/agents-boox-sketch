package io.github.szymonrychu.booxsketch.board

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.content.OnConfigurationChangedProvider
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.onyx.UiRefresher
import io.github.szymonrychu.booxsketch.ui.BoardChrome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Full-screen board with the tool strip dock overlaid on the left edge (popups to its right). [chrome] carries the shell state
 * (dock visibility, tab bar rects, stroke feedback); null, as in previews and tests, keeps the dock
 * always visible. The eraser selection reaches [input] through [StrokeInput.setEraser].
 * [overlays] are window-coordinate rects of anything drawn over the board; they and the toolbar
 * are excluded from raw pen input. For inputs that freeze the e-ink panel, raw drawing starts only
 * after the first frame and toolbar changes are refreshed through a [UiRefresher].
 * [panel], when given, is the side panel drawn over the right edge of the board ([SidePanelHost]),
 * hidden by default behind an indicator; it never resizes the board.
 */
@Suppress("LongParameterList")
@Composable
fun BoardScreen(
    vm: BoardViewModel,
    input: StrokeInput,
    modifier: Modifier = Modifier,
    onSend: (all: Boolean, caption: String?) -> Unit = { _, _ -> },
    overlays: List<Rect> = emptyList(),
    chrome: BoardChrome? = null,
    background: Bitmap? = null,
    toolbar: ToolbarOptions = ToolbarOptions(),
    panel: (@Composable () -> Unit)? = null,
) = BoardScreenWith(
    vm,
    input,
    modifier,
    onSend,
    overlays,
    chrome,
    background = background,
    toolbarOptions = toolbar,
    panel = panel,
)

/** Arguments of the dock slot of [BoardScreenWith]; the default slot is [ToolboxDock]. */
@Suppress("LongParameterList")
internal class DockParams(
    val visible: Boolean,
    val onShow: () -> Unit,
    val onHide: () -> Unit,
    val ui: UiRefresh,
    val onExcludeChange: (List<Rect>) -> Unit,
    val rememberedDockSize: IntSize?,
    val onDockSize: (IntSize) -> Unit,
    val layoutEpoch: Int,
    val onPopupExcludeChange: (List<Rect>) -> Unit,
    val popup: ToolPopup?,
    val popupAnchorY: Float,
    val strip: @Composable () -> Unit,
    val popupContent: @Composable () -> Unit,
)

internal typealias DockContent = @Composable BoxScope.(DockParams) -> Unit

private val DefaultDock: DockContent = { p ->
    ToolboxDock(
        p.visible,
        p.onShow,
        p.ui,
        p.onExcludeChange,
        p.onPopupExcludeChange,
        p.rememberedDockSize,
        p.onDockSize,
        p.popup,
        p.popupAnchorY,
        p.layoutEpoch,
        p.strip,
        p.popupContent,
    )
}

/** [BoardScreen] with the dock replaceable, so tests can drive the dock's exclude reports. */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun BoardScreenWith(
    vm: BoardViewModel,
    input: StrokeInput,
    modifier: Modifier = Modifier,
    onSend: (all: Boolean, caption: String?) -> Unit = { _, _ -> },
    overlays: List<Rect> = emptyList(),
    chrome: BoardChrome? = null,
    dock: DockContent = DefaultDock,
    background: Bitmap? = null,
    toolbarOptions: ToolbarOptions = ToolbarOptions(),
    panel: (@Composable () -> Unit)? = null,
) {
    val notebook by vm.notebook.collectAsState()
    val tool by vm.tool.collectAsState()
    val width by vm.effectiveWidth.collectAsState()
    val eraser by vm.eraser.collectAsState()
    val color by vm.color.collectAsState()
    val preview by vm.preview.collectAsState()
    LaunchedEffect(tool, width) { input.setTool(tool, width) }
    LaunchedEffect(color) { input.setColor(color) }
    LaunchedEffect(eraser) { input.setEraser(eraser) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { vm.flush() } }
    val view = LocalView.current
    // Created during composition so raw drawing is held off before the board first starts it.
    val ui = remember(input, view) { UiRefresher.forView(view, input) }
    // A dock or panel show or hide holds raw drawing (on top of the refresher's hold) until the input runs with the new rects.
    val holds = remember(input) { ChromeHolds(input) }
    // Stop the input first: closing the refresher releases its holds, which must not re-enable a session about to close.
    DisposableEffect(input, ui) {
        onDispose {
            input.stop()
            (ui as? UiRefresher)?.close()
            holds.release()
        }
    }
    // A popup open or close holds raw drawing like a dock change: until the popup rect is (not) in the excludes.
    val toolbox =
        remember(holds) {
            ToolboxState { target, apply -> holds.change(POPUP_KEY, target != null, needsRects = target != null, apply = apply) }
        }
    val dockVisible = chrome?.visible ?: true
    val control = OverlayControl(chrome, holds, toolbox, hasPanel = panel != null)
    // Auto-hide goes through the same hold and full refresh as a tap; it is refused while a stroke is in progress
    // (the hold restarts the input and would drop that stroke), and the shell retries after the next quiet gap.
    DisposableEffect(chrome, input, ui) {
        chrome?.autoHideHandler = {
            if (input.strokeInProgress) {
                false
            } else {
                control.dismissAll(ui)
                true
            }
        }
        onDispose { chrome?.autoHideHandler = null }
    }
    val popup = toolbox.popup
    val shownPage = notebook.pages[notebook.current]
    // Rendered on the main thread only when the page's text or pending rows changed (page switch, send); a new
    // spec version is pre-rendered off the main thread by NotebookSync, so this finds it cached.
    val textLayer = remember(shownPage.text, shownPage.pendingSegments) { vm.textLayers.bitmapFor(shownPage) }
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
            textLayer = textLayer,
            // A finger tap on the board dismisses every overlay in one burst.
            onBoardTap = { control.dismissAll(ui) },
            // Strip, popup or panel open: raw drawing is held off and a pen or finger down on the board dismisses them.
            dismissMode = control.anyVisible,
            onDismiss = { control.dismissAll(ui) },
        )
        // The board ignores system bars; the dock and the panel keep clear of them. The strip also starts below
        // the tab bar (which spans the top edge while the chrome is visible). The panel is drawn on top.
        val dockArea = Modifier.fillMaxSize()
        val density = LocalDensity.current
        Box(
            if (chrome == null) {
                dockArea
            } else {
                val below = maxOf(chrome.insets.getTop(density), chrome.overlays.maxOfOrNull { it.bottom } ?: 0)
                dockArea.windowInsetsPadding(chrome.insets.only(DOCK_INSET_SIDES)).padding(top = with(density) { below.toDp() })
            },
        ) {
            dock(
                DockParams(
                    visible = dockVisible,
                    onShow = control::showDock,
                    onHide = control::hideAll,
                    ui = ui,
                    onExcludeChange = { holds.report(DOCK_KEY, dockVisible, it) },
                    rememberedDockSize = chrome?.dockSize,
                    onDockSize = { chrome?.dockSize = it },
                    layoutEpoch = holds.layoutEpoch,
                    onPopupExcludeChange = { holds.report(POPUP_KEY, popup != null, it) },
                    popup = popup,
                    popupAnchorY = toolbox.anchorY,
                    strip = {
                        ToolStrip(
                            vm,
                            toolbox,
                            onHide = control::hideAll,
                            ui = ui,
                            options = toolbarOptions,
                        )
                    },
                    popupContent = {
                        popup?.let { ToolPopupContent(it, vm, toolbox, onSend, ui, options = toolbarOptions) }
                    },
                ),
            )
        }
        if (panel != null) PanelLayer(chrome, control, ui, holds, panel)
    }
    RelayoutOnConfigurationChange(holds, ui, chrome, hasPanel = panel != null, onLayoutChange = toolbox::close)
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

/**
 * A rotation (the activity handles it, no recreate) lays the board, the panel and the dock out
 * again. The hold is taken in the activity's configuration listener, which runs before the view
 * tree is laid out with the new size, so raw drawing is off before the board restarts its input
 * with the new limit. The full refresh is asked for at the same moment: the refresher runs it after
 * the next draw, which is the new layout (asked later, no further draw might ever come). The hold
 * is released through the usual [ChromeHolds] settle, once the dock and panel reported rects for the new layout.
 */
@Composable
private fun RelayoutOnConfigurationChange(
    holds: ChromeHolds,
    ui: UiRefresh,
    chrome: BoardChrome?,
    hasPanel: Boolean,
    onLayoutChange: () -> Unit,
) {
    val context = LocalContext.current
    val provider = context.findConfigurationProvider() ?: return
    // The layout this composition was built for; the listener compares the next configuration against it.
    val current = layoutOf(LocalConfiguration.current)
    var changes by remember { mutableIntStateOf(0) }
    val latestChrome by rememberUpdatedState(chrome)
    val latestHasPanel by rememberUpdatedState(hasPanel)
    val latestOnLayoutChange by rememberUpdatedState(onLayoutChange)
    DisposableEffect(provider, holds, ui) {
        var seen = current
        val listener =
            Consumer<Configuration> { config ->
                val layout = layoutOf(config)
                if (layout != seen) {
                    seen = layout
                    ui.chromeChanged()
                    // The popup is anchored to a strip button of the old layout: it closes (same burst, same hold).
                    // Called from the configuration listener, outside composition: the snapshot write is global, so it is safe.
                    latestOnLayoutChange()
                    holds.report(LAYOUT_KEY, false, emptyList())
                    // Rects reported for the old layout are stale: settle only on reports from the new one.
                    // The epoch makes the dock and the panel report again even when their rects are equal.
                    holds.relayout(DOCK_KEY, PANEL_KEY)
                    holds.change(LAYOUT_KEY, true, needsRects = false) { }
                    changes++
                }
            }
        provider.addOnConfigurationChangedListener(listener)
        onDispose { provider.removeOnConfigurationChangedListener(listener) }
    }
    LaunchedEffect(changes) {
        if (changes > 0) {
            // Launched by the recomposition of the new layout; one frame on it has been measured and
            // placed. Then wait, like the dock and panel show or hide, until the dock (and the panel,
            // when composed) report non-empty rects for the state they are in.
            withFrameNanos { }
            val reported =
                withTimeoutOrNull(ChromeHolds.RELAYOUT_REPORT_TIMEOUT_MS) {
                    snapshotFlow { holds.overlaysReported(latestChrome, latestHasPanel) }.first { it }
                }
            if (reported == null) {
                // Defence in depth: nothing reported in time, so release with the rects measured before.
                Log.w(
                    TAG,
                    "relayout: no dock or panel report within ${ChromeHolds.RELAYOUT_REPORT_TIMEOUT_MS} ms, releasing with the last rects",
                )
                holds.acceptStaleRects(DOCK_KEY, PANEL_KEY)
            }
            holds.report(LAYOUT_KEY, true, emptyList())
        }
    }
}

private fun layoutOf(config: Configuration) = Triple(config.orientation, config.screenWidthDp, config.screenHeightDp)

private tailrec fun Context.findConfigurationProvider(): OnConfigurationChangedProvider? =
    when (this) {
        is OnConfigurationChangedProvider -> this
        is ContextWrapper -> baseContext.findConfigurationProvider()
        else -> null
    }

/**
 * Show and hide of the chrome over the board (dock, side panel), each through [holds] so raw drawing
 * stays off until the input runs with the new rects. Without a [chrome] nothing changes, so no report
 * would ever settle a hold: the change is applied without one. The same holds for the panel when no
 * panel host is composed ([hasPanel] false).
 */
private class OverlayControl(
    private val chrome: BoardChrome?,
    private val holds: ChromeHolds,
    private val toolbox: ToolboxState,
    private val hasPanel: Boolean,
) {
    val anyVisible: Boolean get() = chrome != null && (chrome.visible || chrome.panelVisible)

    private fun change(
        key: String,
        tracked: Boolean,
        target: Boolean,
        apply: () -> Unit,
    ) = if (tracked) holds.change(key, target, apply = apply) else apply()

    fun showDock() = change(DOCK_KEY, chrome != null, true) { chrome?.show() }

    fun showPanel() = change(PANEL_KEY, chrome != null && hasPanel, true) { chrome?.showPanel() }

    fun hidePanel() = change(PANEL_KEY, chrome != null && hasPanel, false) { chrome?.hidePanel() }

    /** Full refresh plus [hideAll] when anything is shown; with everything hidden it does nothing (no refresh). */
    fun dismissAll(ui: UiRefresh) {
        if (!anyVisible) return
        ui.chromeChanged()
        hideAll()
    }

    /** Hides the dock and the panel in one burst: one hold, released once both report their hidden state. */
    fun hideAll() {
        if (chrome == null) return
        val panelWasVisible = chrome.panelVisible
        if (chrome.visible) {
            toolbox.close()
            change(DOCK_KEY, true, false) { chrome.hide() }
        }
        if (panelWasVisible) hidePanel()
    }
}

/** The side panel host over the right edge, inside the system bars, reporting its rects to [holds]. */
@Composable
private fun BoxScope.PanelLayer(
    chrome: BoardChrome?,
    control: OverlayControl,
    ui: UiRefresh,
    holds: ChromeHolds,
    panel: @Composable () -> Unit,
) {
    val visible = chrome?.panelVisible ?: false
    val area = Modifier.matchParentSize()
    Box(if (chrome == null) area else area.windowInsetsPadding(chrome.insets.only(PANEL_INSET_SIDES))) {
        SidePanelHost(
            visible = visible,
            onShow = control::showPanel,
            onHide = control::hidePanel,
            ui = ui,
            onExcludeChange = { holds.report(PANEL_KEY, visible, it) },
            layoutEpoch = holds.layoutEpoch,
            panel = panel,
        )
    }
}

private val DOCK_INSET_SIDES = WindowInsetsSides.Bottom + WindowInsetsSides.Start

private val PANEL_INSET_SIDES = WindowInsetsSides.Vertical + WindowInsetsSides.End

private const val DOCK_KEY = "dock"
private const val PANEL_KEY = "panel"
private const val POPUP_KEY = "popup"
private const val LAYOUT_KEY = "layout"
private const val TAG = "BoardScreen"

/** [overlaySettled] for the dock with a change pending to [awaiting] (null: no change pending); the dock always needs rects. */
internal fun dockSettled(
    awaiting: Boolean?,
    visible: Boolean,
    rects: List<Rect>,
): Boolean = awaiting != null && overlaySettled(awaiting, needsRects = true, visible = visible, rects = rects)

/**
 * True when the dock (and the panel, when composed) reported non-empty rects for the state [chrome] shows;
 * with no chrome there is nothing to wait for.
 */
private fun ChromeHolds.overlaysReported(
    chrome: BoardChrome?,
    hasPanel: Boolean,
): Boolean =
    chrome == null ||
        (reported(DOCK_KEY, chrome.visible) && (!hasPanel || reported(PANEL_KEY, chrome.panelVisible)))
