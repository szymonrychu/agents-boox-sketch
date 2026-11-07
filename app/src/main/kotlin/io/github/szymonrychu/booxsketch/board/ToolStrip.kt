package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.Tool

internal val STRIP_WIDTH = 56.dp
internal val STRIP_BUTTON = 48.dp
internal val SWATCH_FRAME = 3.dp
private const val BUTTON_COUNT = 10
private val LocalButtonHeight = compositionLocalOf { STRIP_BUTTON }

/** Test tag of a strip button. */
internal fun stripTag(name: String) = "strip_$name"

/** What differs on a document page: no page button (pages are the document's) and its own Send label. */
data class ToolbarOptions(
    val pageControls: Boolean = true,
    val sendLabel: String = "Send",
)

/** Runs [block], then reports a settled UI change. */
internal fun UiRefresh.act(block: () -> Unit): () -> Unit =
    {
        block()
        uiChanged()
    }

/**
 * The BOOX Notes style tool strip: a [STRIP_WIDTH] wide column of icon buttons, top to bottom pen (the
 * selected pen type), eraser, rectangle, arrow, colour, undo, redo, pages (with "2/3"), send and a hide
 * chevron. The selected tool has a [SWATCH_FRAME] black frame. A tap on an unselected tool selects it; a tap
 * on the selected tool, colour, pages or send opens or closes its popup ([ToolboxState]). Every state
 * change is reported to [ui]: a popup change as a full refresh, anything else as a settled change.
 * [onHide] is called after the full refresh of the hide tap was asked for.
 */
@Suppress("LongMethod")
@Composable
fun ToolStrip(
    vm: BoardViewModel,
    state: ToolboxState,
    onHide: () -> Unit,
    ui: UiRefresh,
    modifier: Modifier = Modifier,
    options: ToolbarOptions = ToolbarOptions(),
) {
    val notebook by vm.notebook.collectAsState()
    val tool by vm.tool.collectAsState()
    val eraser by vm.eraser.collectAsState()
    val color by vm.color.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val selected =
        if (eraser) {
            ToolPopup.ERASER
        } else if (tool.isShape) {
            ToolPopup.SHAPE
        } else {
            ToolPopup.PEN
        }
    SideEffect {
        if (!tool.isShape) state.lastPen = tool
        // A tool switched from elsewhere (eraser, undo of a shape pick) closes the popup of the tool left behind.
        state.onToolChanged(selected)
    }
    val pen = if (tool.isShape) state.lastPen else tool
    // The box button stands for the rectangle and the image region (picked in the shape popup) and shows the one in use.
    val rectangle = !eraser && tool.isBox
    val box = if (tool == Tool.IMAGE_REGION) Tool.IMAGE_REGION else Tool.RECTANGLE
    val arrow = !eraser && tool == Tool.ARROW

    BoxWithConstraints(modifier.width(STRIP_WIDTH).fillMaxHeight()) {
        // 48 dp buttons; on a window too short for all of them they shrink to fit instead of scrolling or clipping.
        val height = minOf(STRIP_BUTTON, maxHeight / (if (options.pageControls) BUTTON_COUNT else BUTTON_COUNT - 1))
        CompositionLocalProvider(LocalButtonHeight provides height) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StripButton("pen", pen.wire, selected == ToolPopup.PEN, onClick = { y ->
                    state.press(ToolPopup.PEN, selected == ToolPopup.PEN, y, ui) { vm.setTool(state.lastPen) }
                }) { ToolIcon(pen.iconKind()) }
                StripButton("eraser", "Eraser", eraser, onClick = { y ->
                    state.press(ToolPopup.ERASER, eraser, y, ui) { vm.setEraser(true) }
                }) { ToolIcon(IconKind.ERASER) }
                StripButton("rectangle", "Rectangle", rectangle, onClick = { y ->
                    state.press(ToolPopup.SHAPE, rectangle, y, ui) { vm.setTool(box) }
                }) { ToolIcon(box.iconKind()) }
                StripButton("arrow", "Arrow", arrow, onClick = { y ->
                    state.press(ToolPopup.SHAPE, arrow, y, ui) { vm.setTool(Tool.ARROW) }
                }) { ToolIcon(IconKind.ARROW) }
                StripButton("colour", "Colour", state.popup == ToolPopup.COLOUR, onClick = { y ->
                    state.press(ToolPopup.COLOUR, false, y, ui) {}
                }) { ToolIcon(IconKind.COLOUR, fill = Color(color.argb)) }
                StripButton("undo", "Undo", enabled = canUndo, onClick = { ui.act(vm::undo)() }) { ToolIcon(IconKind.UNDO) }
                StripButton("redo", "Redo", enabled = canRedo, onClick = { ui.act(vm::redo)() }) { ToolIcon(IconKind.REDO) }
                if (options.pageControls) {
                    StripButton("pages", "Pages", state.popup == ToolPopup.PAGES, onClick = { y ->
                        state.press(ToolPopup.PAGES, false, y, ui) {}
                    }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ToolIcon(IconKind.PAGES, Modifier.size(PAGES_ICON))
                            Text(
                                "${notebook.current + 1}/${notebook.pages.size}",
                                fontSize = 12.sp,
                                lineHeight = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                StripButton("send", options.sendLabel, state.popup == ToolPopup.SEND, onClick = { y ->
                    state.press(ToolPopup.SEND, false, y, ui) {}
                }) { ToolIcon(IconKind.SEND) }
                StripButton("hide", HIDE_TOOLS_LABEL, onClick = {
                    ui.chromeChanged()
                    onHide()
                }) { ToolIcon(IconKind.HIDE) }
            }
        }
    }
}

private val PAGES_ICON = 20.dp

/** A tap on a strip button: [ToolboxState.tap], the selection it asks for, then the matching refresh. */
private fun ToolboxState.press(
    target: ToolPopup,
    alreadySelected: Boolean,
    anchorY: Float,
    ui: UiRefresh,
    select: () -> Unit,
) {
    this.anchorY = anchorY
    // A popup change is a full refresh: asked for first, so the refresher's hold is taken before the board's.
    val changes = popupAfterTap(target, alreadySelected) != popup
    if (changes) ui.chromeChanged()
    if (tap(target, alreadySelected) == TapResult.SelectOnly) select()
    if (!changes) ui.uiChanged()
}

/**
 * One 48 dp strip button inside the 56 dp column. A null [selected] is a plain (not selectable) button; a
 * selected one gets the black frame, so it reads on e-ink without colour. [onClick] gets the window y of the
 * button (where its popup is anchored).
 */
@Composable
private fun StripButton(
    name: String,
    description: String,
    selected: Boolean? = null,
    enabled: Boolean = true,
    onClick: (anchorY: Float) -> Unit,
    icon: @Composable () -> Unit,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val click = { onClick(coords?.positionInWindow()?.y ?: 0f) }
    val action =
        if (selected == null) {
            Modifier.clickable(enabled = enabled, interactionSource = null, indication = null, role = Role.Button, onClick = click)
        } else {
            Modifier.selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = click,
            )
        }
    val frame = if (selected == true) Modifier.border(SWATCH_FRAME, Color.Black, RoundedCornerShape(8.dp)) else Modifier
    Box(
        Modifier
            .size(STRIP_BUTTON, LocalButtonHeight.current)
            .testTag(stripTag(name))
            .onGloballyPositioned { coords = it }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .then(action)
            .then(frame)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

private const val DISABLED_ALPHA = 0.35f
