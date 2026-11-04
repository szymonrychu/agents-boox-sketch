package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.ui.NoIndication

internal const val TAG_PANEL_HOST = "side_panel"
internal const val TAG_PANEL_INDICATOR = "panel_indicator"
internal const val TAG_PANEL_HIDE = "panel_hide"
internal const val SHOW_PANEL_LABEL = "Show panel"
internal const val HIDE_PANEL_LABEL = "Hide panel"

/** Panel width as a fraction of its parent when the parent is wider than tall (landscape). */
internal const val PANEL_WIDE_FRACTION = 0.45f

/** Panel width as a fraction of its parent otherwise (portrait); the panel is never below the board. */
internal const val PANEL_TALL_FRACTION = 0.8f

internal fun panelWidthFraction(
    width: Int,
    height: Int,
): Float = if (width > height) PANEL_WIDE_FRACTION else PANEL_TALL_FRACTION

/**
 * The side panel as an overlay on the right edge of the board, full height of this box; hidden it
 * collapses to a 48 dp indicator at the right centre. Like [ToolboxDock], every show or hide tells
 * [ui] first (hold, full refresh) and then flips the state, and [onExcludeChange] gets the pen
 * exclude rects (window px): the panel while [visible], the indicator otherwise. The panel rect is
 * estimated from the parent size until measured, so the first frame already excludes it. Taps on
 * the panel never fall through to the board.
 */
@Suppress("LongParameterList")
@Composable
fun BoxScope.SidePanelHost(
    visible: Boolean,
    onShow: () -> Unit,
    onHide: () -> Unit,
    ui: UiRefresh,
    onExcludeChange: (List<Rect>) -> Unit,
    layoutEpoch: Int = 0,
    panel: @Composable () -> Unit,
) {
    var parent by remember { mutableStateOf<Rect?>(null) }
    // Keyed on visibility so a measurement never outlives the state it was taken from.
    val measured = remember(visible) { mutableStateOf<Rect?>(null) }

    Box(Modifier.matchParentSize().onGloballyPositioned { parent = it.windowRect() })

    val estimate =
        remember(parent) {
            parent?.let { p ->
                val w = (p.width() * panelWidthFraction(p.width(), p.height())).toInt()
                Rect(p.right - w, p.top, p.right, p.bottom)
            }
        }
    val excludes = dockExcludeRects(visible, measured.value ?: estimate, measured.value)
    var reported by remember { mutableStateOf<List<Rect>?>(null) }
    var reportedEpoch by remember { mutableStateOf(layoutEpoch) }
    SideEffect {
        // A new layout epoch reports again even when the rects are equal (a relayout waits for it).
        if (excludes != reported || layoutEpoch != reportedEpoch) {
            reported = excludes
            reportedEpoch = layoutEpoch
            onExcludeChange(excludes)
        }
    }

    BoxWithConstraints(Modifier.matchParentSize()) {
        if (visible) {
            val fraction = if (maxWidth > maxHeight) PANEL_WIDE_FRACTION else PANEL_TALL_FRACTION
            PanelOverlay(fraction, ui, onHide, { measured.value = it.windowRect() }, panel)
        } else {
            PanelIndicator(ui, onShow) { measured.value = it.windowRect() }
        }
    }
}

@Composable
private fun BoxScope.PanelOverlay(
    fraction: Float,
    ui: UiRefresh,
    onHide: () -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit,
    panel: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                // Swallow every pointer event, so a tap on the panel never reaches the board under it.
                .pointerInput(Unit) { awaitEachGesture { awaitPointerEvent() } }
                .background(Color.White)
                .drawBehind { drawLine(Color.Black, Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) }
                .testTag(TAG_PANEL_HOST)
                .onGloballyPositioned(onPositioned),
        ) {
            TextButton(
                onClick = {
                    ui.chromeChanged()
                    onHide()
                },
                modifier = Modifier.align(Alignment.End).heightIn(min = INDICATOR_TARGET).testTag(TAG_PANEL_HIDE),
            ) { Text(">", Modifier.semantics { contentDescription = HIDE_PANEL_LABEL }, color = Color.Black) }
            Box(Modifier.weight(1f).fillMaxWidth()) { panel() }
        }
    }
}

@Composable
private fun BoxScope.PanelIndicator(
    ui: UiRefresh,
    onShow: () -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit,
) {
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .size(INDICATOR_TARGET)
            .testTag(TAG_PANEL_INDICATOR)
            .onGloballyPositioned(onPositioned)
            .semantics { contentDescription = SHOW_PANEL_LABEL }
            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                ui.chromeChanged()
                onShow()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(INDICATOR_VISUAL).border(BorderStroke(2.dp, Color.Black)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Text("<", color = Color.Black) }
    }
}
