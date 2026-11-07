// one small composable per popup and widget
@file:Suppress("TooManyFunctions")

package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool

internal const val TAG_SEND = "send"
internal const val TAG_SEND_ALL = "send_all"
internal const val TAG_PAGE_LABEL = "page_label"
internal const val TAG_WIDTH_SLIDER = "width_slider"
internal const val SEND_ALL_LABEL = "Send all pages"
internal const val MIN_WIDTH = 1f
internal const val MAX_WIDTH = 24f
internal val SWATCH_TARGET = 48.dp
internal val SWATCH_DOT = 34.dp
internal val SWATCH_MARK = 12.dp
private val PEN_CELL_WIDTH = 60.dp
private val PEN_CELL_HEIGHT = 64.dp
private val BUTTON_MIN_HEIGHT = 48.dp

/** Test tag of the swatch for [color]. */
internal fun colorTag(color: PenColor) = "color_${color.wire}"

/** Test tag of the shape button for [tool] in the shape popup. */
internal fun shapeTag(tool: Tool) = "shape_${tool.wire}"

/** Test tag of the pen type button for [tool]. */
internal fun penTag(tool: Tool) = "pen_${tool.wire}"

/** The pen types, in the order of the pen popup grid. */
private val PENS = Tool.entries.filter { !it.isShape }

/** The shapes, in the order of the shape popup row. */
private val SHAPES = Tool.entries.filter { it.isShape }

/**
 * The popup of [kind], a white bordered box sized to its content (no scrolling), placed by the dock next to
 * the strip. PEN: the six pen types and the width slider with a live sample line (drags report unsettled
 * changes, the release a settled one). ERASER: a hint and Clear page. SHAPE: the shapes (rectangle,
 * arrow, image region) over the colour grid; COLOUR: the colour grid (shapes have a fixed width, so no
 * slider). PAGES: previous, next, add, delete and the page label.
 * SEND: the caption (kept in [state]) and the send buttons; [onSend] gets `all = false` from the first
 * button (long press: all pages) and `all = true` from "Send all pages", the caption null when blank, and
 * the popup closes after it. Every change is reported to [ui].
 */
@Composable
fun ToolPopupContent(
    kind: ToolPopup,
    vm: BoardViewModel,
    state: ToolboxState,
    onSend: (all: Boolean, caption: String?) -> Unit,
    ui: UiRefresh,
    modifier: Modifier = Modifier,
    options: ToolbarOptions = ToolbarOptions(),
) {
    Column(
        modifier
            .width(popupWidth(kind))
            .background(Color.White)
            .border(1.dp, Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (kind) {
            ToolPopup.PEN -> {
                PenPopup(vm, ui)
            }

            ToolPopup.ERASER -> {
                EraserPopup(vm, ui)
            }

            ToolPopup.SHAPE -> {
                ShapeRow(vm, ui)
                ColourGrid(vm, ui)
            }

            ToolPopup.COLOUR -> {
                ColourGrid(vm, ui)
            }

            ToolPopup.PAGES -> {
                PagesPopup(vm, ui)
            }

            ToolPopup.SEND -> {
                SendPopup(state, onSend, ui, options)
            }
        }
    }
}

private fun popupWidth(kind: ToolPopup): Dp =
    when (kind) {
        ToolPopup.PEN -> PEN_CELL_WIDTH * 3 + 16.dp
        ToolPopup.SHAPE, ToolPopup.COLOUR -> SWATCH_TARGET * 3 + 16.dp
        ToolPopup.ERASER -> 200.dp
        ToolPopup.PAGES -> 240.dp
        ToolPopup.SEND -> 300.dp
    }

@Composable
private fun PenPopup(
    vm: BoardViewModel,
    ui: UiRefresh,
) {
    val tool by vm.tool.collectAsState()
    val eraser by vm.eraser.collectAsState()
    val width by vm.width.collectAsState()
    for (row in PENS.chunked(3)) {
        Row {
            for (t in row) {
                val selected = !eraser && tool == t
                Column(
                    Modifier
                        .size(PEN_CELL_WIDTH, PEN_CELL_HEIGHT)
                        .testTag(penTag(t))
                        .semantics { contentDescription = t.wire }
                        .selectable(
                            selected = selected,
                            interactionSource = null,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = ui.act { vm.setTool(t) },
                        ).then(if (selected) Modifier.border(SWATCH_FRAME, Color.Black, RoundedCornerShape(8.dp)) else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ToolIcon(t.iconKind(), Modifier.size(32.dp))
                    Text(t.wire, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Width")
        Slider(
            value = width,
            onValueChange = {
                vm.setWidth(it)
                ui.uiChanged(settled = false)
            },
            onValueChangeFinished = { ui.uiChanged() },
            valueRange = MIN_WIDTH..MAX_WIDTH,
            modifier = Modifier.weight(1f).testTag(TAG_WIDTH_SLIDER),
        )
    }
    val density = LocalDensity.current.density
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        drawLine(
            Color.Black,
            Offset(0f, size.height / 2),
            Offset(size.width, size.height / 2),
            strokeWidth = width * density / 2,
            cap = StrokeCap.Round,
        )
    }
}

/** One 48 dp button per shape tool, the selected one framed like a swatch. */
@Composable
private fun ShapeRow(
    vm: BoardViewModel,
    ui: UiRefresh,
) {
    val tool by vm.tool.collectAsState()
    val eraser by vm.eraser.collectAsState()
    Row {
        for (t in SHAPES) {
            val selected = !eraser && tool == t
            Box(
                Modifier
                    .size(SWATCH_TARGET)
                    .testTag(shapeTag(t))
                    .semantics { contentDescription = t.wire }
                    .selectable(
                        selected = selected,
                        interactionSource = null,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = ui.act { vm.setTool(t) },
                    ).then(if (selected) Modifier.border(SWATCH_FRAME, Color.Black, RoundedCornerShape(8.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                ToolIcon(t.iconKind(), Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun EraserPopup(
    vm: BoardViewModel,
    ui: UiRefresh,
) {
    Text("Drag over strokes to erase them")
    PopupButton("Clear page", onClick = ui.act(vm::clear), tag = "eraser_clear")
}

/**
 * Three by two swatches. The selected one gets a black frame around the dot and a white centre mark, so it
 * reads on e-ink without relying on the colour (black included).
 */
@Composable
private fun ColourGrid(
    vm: BoardViewModel,
    ui: UiRefresh,
) {
    val color by vm.color.collectAsState()
    val eraser by vm.eraser.collectAsState()
    for (row in PenColor.entries.chunked(3)) {
        Row {
            for (c in row) Swatch(c, selected = !eraser && c == color, onClick = ui.act { vm.setColor(c) })
        }
    }
}

@Composable
private fun Swatch(
    c: PenColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(SWATCH_TARGET)
            .testTag(colorTag(c))
            .semantics { contentDescription = "Colour ${c.wire}" }
            .selectable(selected = selected, interactionSource = null, indication = null, role = Role.RadioButton, onClick = onClick)
            .then(if (selected) Modifier.border(SWATCH_FRAME, Color.Black, RoundedCornerShape(8.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SWATCH_DOT)
                .clip(CircleShape)
                .background(Color(c.argb))
                .border(1.dp, Color.Black, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(SWATCH_MARK)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(1.dp, Color.Black, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun PagesPopup(
    vm: BoardViewModel,
    ui: UiRefresh,
) {
    val notebook by vm.notebook.collectAsState()
    val last = notebook.pages.size - 1
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PopupButton("Previous", enabled = notebook.current > 0, onClick = ui.act { vm.selectPage(notebook.current - 1) })
        Text("page ${notebook.current + 1} of ${last + 1}", Modifier.testTag(TAG_PAGE_LABEL))
        PopupButton("Next", enabled = notebook.current < last, onClick = ui.act { vm.selectPage(notebook.current + 1) })
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        PopupButton("Add page", onClick = ui.act(vm::addPage))
        PopupButton("Delete page", onClick = ui.act { vm.deletePage(notebook.current) })
    }
}

@Composable
private fun SendPopup(
    state: ToolboxState,
    onSend: (all: Boolean, caption: String?) -> Unit,
    ui: UiRefresh,
    options: ToolbarOptions,
) {
    val send = { all: Boolean ->
        // Full refresh first (the refresher's hold before the board's), as in the strip's popup taps.
        ui.chromeChanged()
        onSend(all, state.caption.ifBlank { null })
        state.close()
    }
    OutlinedTextField(
        value = state.caption,
        onValueChange = {
            state.caption = it
            ui.uiChanged(settled = false)
        },
        label = { Text("Caption") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SendButton(options.sendLabel, onTap = { send(false) }, onLongPress = { if (options.pageControls) send(true) })
        if (options.pageControls) PopupButton(SEND_ALL_LABEL, onClick = { send(true) }, tag = TAG_SEND_ALL)
    }
}

/** A 48 dp tall text button with a black outline, so it reads as a button on e-ink. */
@Composable
private fun PopupButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .heightIn(min = BUTTON_MIN_HEIGHT)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .border(1.dp, Color.Black, RoundedCornerShape(8.dp)),
    ) { Text(text, color = Color.Black) }
}

/** The send button: a tap sends the changed pages, a long press all of them (a Button would swallow the long press). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(
    label: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .testTag(TAG_SEND)
            .heightIn(min = BUTTON_MIN_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .combinedClickable(interactionSource = null, indication = null, onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White)
    }
}
