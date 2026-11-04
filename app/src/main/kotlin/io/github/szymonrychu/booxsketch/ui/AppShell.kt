package io.github.szymonrychu.booxsketch.ui

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay

/** What the board slot sees of the shell: tab bar visibility, its window rects and stroke feedback. */
interface BoardChrome {
    val visible: Boolean

    /** Window rects of shell UI drawn over the board (the tab bar while visible). */
    val overlays: List<Rect>

    /** Last measured toolbar dock size in px; null until measured. Lets the next show exclude the dock at once. */
    var dockSize: IntSize?

    /** System bar insets the chrome drawn over the board (tab bar, dock) keeps clear; the board itself ignores them. */
    val insets: WindowInsets

    /**
     * Set by the board slot: runs the auto-hide on its terms (input hold, full refresh) and returns
     * false to refuse it, e.g. while a stroke is in progress; the shell then retries after a quiet gap.
     * Null: the shell hides the chrome directly.
     */
    var autoHideHandler: (() -> Boolean)?

    fun show()

    fun hide()

    fun onStrokeCommitted()
}

/** Called after a tab change that does not land on the board, to clear e-ink ghosting. */
fun interface ShellRefresh {
    fun tabChanged(view: View)

    companion object {
        val None = ShellRefresh { }
    }
}

private class ShellChrome(
    private val state: ShellState,
    private val currentInsets: () -> WindowInsets,
) : BoardChrome {
    override val insets: WindowInsets get() = currentInsets()

    var barRect by mutableStateOf<Rect?>(null)

    override val visible: Boolean get() = state.chromeVisible
    override val overlays: List<Rect> get() = if (visible) listOfNotNull(barRect) else emptyList()

    override var dockSize: IntSize?
        get() = state.dockSize
        set(value) {
            state.dockSize = value
        }

    override var autoHideHandler: (() -> Boolean)? = null

    /** True when the auto-hide is done (the chrome is hidden); false when the board slot refused it. */
    fun requestAutoHide(): Boolean =
        autoHideHandler?.invoke() ?: run {
            state.hide()
            true
        }

    override fun show() = state.show()

    override fun hide() = state.hide()

    override fun onStrokeCommitted() = state.onStrokeCommitted()
}

/**
 * Top-level shell: the board tab hosts [board] full screen with the tab bar overlaid (and
 * auto-hidden after strokes); every other tab shows its content below an always visible tab bar.
 * The board is disposed while another tab is selected.
 */
@Composable
fun AppShell(
    tabs: TabRegistry,
    board: @Composable (BoardChrome) -> Unit,
    modifier: Modifier = Modifier,
    state: ShellState = rememberShellState(),
    refresh: ShellRefresh = ShellRefresh.None,
    insets: WindowInsets = WindowInsets.safeDrawing,
) {
    val view = LocalView.current
    val latestInsets by rememberUpdatedState(insets)
    val chrome = remember(state) { ShellChrome(state) { latestInsets } }
    val specs = tabs.tabs
    val selectedId = if (specs.any { it.id == state.selected }) state.selected else BOARD_TAB_ID
    val onBoard = selectedId == BOARD_TAB_ID

    LaunchedEffect(state.strokeTick) {
        if (state.strokeTick > 0) {
            do {
                delay(ShellState.AUTO_HIDE_AFTER_STROKE_MS)
            } while (!chrome.requestAutoHide())
        }
    }
    var firstTab by remember { mutableStateOf(true) }
    LaunchedEffect(selectedId) {
        if (firstTab) {
            firstTab = false
        } else if (selectedId != BOARD_TAB_ID) {
            refresh.tabChanged(view)
        }
    }

    // The board is full screen: only the chrome over it and the other tabs keep clear of the system bars.
    Box(modifier.fillMaxSize().background(Color.White)) {
        if (onBoard) {
            board(chrome)
            if (state.chromeVisible) {
                TabBar(
                    tabs = specs,
                    selectedId = selectedId,
                    onSelect = state::select,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .onGloballyPositioned {
                                val b = it.boundsInWindow()
                                chrome.barRect = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                            },
                )
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
                TabBar(tabs = specs, selectedId = selectedId, onSelect = state::select)
                Box(Modifier.weight(1f)) {
                    specs.first { it.id == selectedId }.content(Modifier.fillMaxSize())
                }
            }
        }
    }
}
