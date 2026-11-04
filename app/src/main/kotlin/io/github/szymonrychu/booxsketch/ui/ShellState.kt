package io.github.szymonrychu.booxsketch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize

/** Tab selection, board-chrome (tab bar, dock) and side panel visibility of the app shell. */
@Stable
class ShellState(
    selected: String = BOARD_TAB_ID,
    chromeVisible: Boolean = true,
) {
    var selected: String by mutableStateOf(selected)
        private set

    var chromeVisible: Boolean by mutableStateOf(chromeVisible)
        private set

    /** Side panel overlay over the board. Hidden by default and not saved: a restart starts hidden. */
    var panelVisible: Boolean by mutableStateOf(false)
        private set

    /** Last measured dock size in px; null until measured. Not saved. */
    var dockSize: IntSize? by mutableStateOf(null)

    private var tick by mutableIntStateOf(0)

    /** Incremented on every committed stroke while armed; LaunchedEffect key for auto-hide. */
    val strokeTick: Int get() = tick

    /** Selects [id]; switching to the board shows the chrome. */
    fun select(id: String) {
        selected = id
        if (id == BOARD_TAB_ID) chromeVisible = true
    }

    fun show() {
        chromeVisible = true
    }

    /** Hides the chrome and the side panel. */
    fun hide() {
        chromeVisible = false
        panelVisible = false
    }

    fun showPanel() {
        panelVisible = true
    }

    fun hidePanel() {
        panelVisible = false
    }

    /** Arms the auto-hide, only while the chrome or the panel is visible. */
    fun onStrokeCommitted() {
        if (chromeVisible || panelVisible) tick++
    }

    companion object {
        const val AUTO_HIDE_AFTER_STROKE_MS = 600L

        val Saver: Saver<ShellState, Any> =
            Saver(
                save = { listOf(it.selected, it.chromeVisible) },
                restore = { saved ->
                    val values = saved as List<*>
                    ShellState(values[0] as String, values[1] as Boolean)
                },
            )
    }
}

@Composable
fun rememberShellState(): ShellState = rememberSaveable(saver = ShellState.Saver) { ShellState() }
