package io.github.szymonrychu.booxsketch.board

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.szymonrychu.booxsketch.model.Tool

/** What a popup next to the tool strip is about. PEN, ERASER and SHAPE belong to the selected tool. */
enum class ToolPopup { PEN, ERASER, SHAPE, COLOUR, PAGES, SEND }

private val ToolPopup.toolBound: Boolean get() = this == ToolPopup.PEN || this == ToolPopup.ERASER || this == ToolPopup.SHAPE

/** What a tap on a strip button asks of the caller. */
enum class TapResult {
    /** An unselected tool was tapped: select it, no popup. */
    SelectOnly,

    /** The popup of the button opened or closed. */
    TogglePopup,
}

/**
 * The popup of the tool strip: at most one is open. A tap on an unselected tool selects it and closes any
 * popup; a tap on the selected tool, or on the colour, pages or send button, toggles its popup.
 * Every change of [popup] goes through [gate] with the new target, so the board can hold raw drawing
 * around it; [gate] must call `apply` exactly once. Not saved across rotation (the activity handles
 * the configuration change and keeps this instance).
 */
@Stable
class ToolboxState(
    private val gate: (target: ToolPopup?, apply: () -> Unit) -> Unit = { _, apply -> apply() },
) {
    var popup: ToolPopup? by mutableStateOf(null)
        private set

    /** Last pen type, so the pen button restores it after a shape or the eraser. */
    var lastPen: Tool by mutableStateOf(Tool.BALLPOINT)

    /** The caption typed in the send popup; kept here so closing the popup does not lose it. */
    var caption: String by mutableStateOf("")

    /** Window y (px) of the button last tapped; the popup is placed next to it. Read when a popup opens. */
    var anchorY: Float = 0f

    private fun set(target: ToolPopup?) {
        if (target != popup) gate(target) { popup = target }
    }

    /** The popup a tap on [target] leads to (null: none), without changing anything. */
    fun popupAfterTap(
        target: ToolPopup,
        alreadySelected: Boolean,
    ): ToolPopup? =
        when {
            target.toolBound && !alreadySelected -> null
            popup == target -> null
            else -> target
        }

    fun tap(
        target: ToolPopup,
        alreadySelected: Boolean,
    ): TapResult {
        val next = popupAfterTap(target, alreadySelected)
        set(next)
        return if (target.toolBound && !alreadySelected) TapResult.SelectOnly else TapResult.TogglePopup
    }

    fun close() = set(null)

    /** The selected tool kind changed: closes the popup of a tool that is no longer selected. */
    fun onToolChanged(selected: ToolPopup?) {
        val p = popup ?: return
        if (p.toolBound && p != selected) set(null)
    }
}
