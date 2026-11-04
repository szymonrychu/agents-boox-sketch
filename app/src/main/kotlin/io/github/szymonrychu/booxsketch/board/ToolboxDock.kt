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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.ink.UiRefresh
import io.github.szymonrychu.booxsketch.ui.NoIndication

internal const val TAG_DOCK = "toolbar_dock"
internal const val TAG_INDICATOR = "toolbar_indicator"
internal const val TAG_POPUP = "tool_popup"
internal const val TAG_HIDE = "strip_hide"
internal const val SHOW_TOOLS_LABEL = "Show tools"
internal const val HIDE_TOOLS_LABEL = "Hide tools"
internal val INDICATOR_TARGET = 48.dp
internal val INDICATOR_VISUAL = 32.dp

/** Exclude rects (window px) for the dock state: dock rect when visible, indicator target rect when hidden. */
internal fun dockExcludeRects(
    visible: Boolean,
    dock: Rect?,
    indicator: Rect?,
): List<Rect> = listOfNotNull(if (visible) dock else indicator)

internal fun LayoutCoordinates.windowRect(): Rect {
    val b = boundsInWindow()
    return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
}

/**
 * The tool [strip] on the left edge of the parent (full height), with the open [popup] ([popupContent])
 * to its right, aligned to the tapped button (window y [popupAnchorY]) and clamped inside the parent. It is
 * shown or collapsed to a tap indicator on the same edge, and keeps the pen exclude rects in step:
 * [onExcludeChange] gets the strip (the indicator when hidden), [onPopupExcludeChange] the popup rect
 * (empty while none is open, and until the open popup is measured, so the caller's hold waits for it).
 * When shown, the strip rect is estimated from [rememberedDockSize] until measured, so the first frame
 * already excludes it. Taps on the strip and the popup never reach the board.
 */
@Suppress("LongParameterList")
@Composable
fun BoxScope.ToolboxDock(
    visible: Boolean,
    onShow: () -> Unit,
    ui: UiRefresh,
    onExcludeChange: (List<Rect>) -> Unit,
    onPopupExcludeChange: (List<Rect>) -> Unit,
    rememberedDockSize: IntSize?,
    onDockSize: (IntSize) -> Unit,
    popup: ToolPopup?,
    popupAnchorY: Float,
    layoutEpoch: Int = 0,
    strip: @Composable () -> Unit,
    popupContent: @Composable () -> Unit,
) {
    var parent by remember { mutableStateOf<Rect?>(null) }
    // Keyed on visibility so a measurement never outlives the state it was taken from; the popup likewise on its kind.
    val measured = remember(visible) { mutableStateOf<Rect?>(null) }
    val popupMeasured = remember(popup) { mutableStateOf<Rect?>(null) }

    Box(Modifier.matchParentSize().onGloballyPositioned { parent = it.windowRect() })

    val estimate =
        remember(parent, rememberedDockSize) {
            val p = parent
            val s = rememberedDockSize
            if (p == null || s == null) null else Rect(p.left, p.top, p.left + s.width, p.bottom)
        }
    val excludes = dockExcludeRects(visible, measured.value ?: estimate, measured.value)
    val popupExcludes = if (visible && popup != null) listOfNotNull(popupMeasured.value) else emptyList()
    var reported by remember { mutableStateOf<List<Rect>?>(null) }
    var popupReported by remember { mutableStateOf<List<Rect>?>(null) }
    var reportedEpoch by remember { mutableStateOf(layoutEpoch) }
    SideEffect {
        // A new layout epoch reports again even when the rects are equal (a relayout waits for it).
        val again = layoutEpoch != reportedEpoch
        reportedEpoch = layoutEpoch
        if (excludes != reported || again) {
            reported = excludes
            onExcludeChange(excludes)
        }
        if (popupExcludes != popupReported || again) {
            popupReported = popupExcludes
            onPopupExcludeChange(popupExcludes)
        }
    }

    if (visible) {
        DockStrip(
            onPositioned = {
                measured.value = it.windowRect()
                onDockSize(IntSize(it.size.width, it.size.height))
            },
            strip = strip,
        )
        if (popup != null) {
            PopupHost(parent, popupAnchorY, { popupMeasured.value = it.windowRect() }, popupContent)
        }
    } else {
        ShowIndicator(ui = ui, onShow = onShow, onPositioned = { measured.value = it.windowRect() })
    }
}

@Composable
private fun BoxScope.DockStrip(
    onPositioned: (LayoutCoordinates) -> Unit,
    strip: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                // Swallow every pointer event, so a tap between the strip's buttons never reaches the board.
                .pointerInput(Unit) { awaitEachGesture { awaitPointerEvent() } }
                .background(Color.White)
                .drawBehind { drawLine(Color.Black, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
                .testTag(TAG_DOCK)
                .onGloballyPositioned(onPositioned),
        ) { strip() }
    }
}

/** Places the popup right of the strip at the anchor's height, kept inside the parent; swallows its own taps. */
@Composable
private fun BoxScope.PopupHost(
    parent: Rect?,
    anchorY: Float,
    onPositioned: (LayoutCoordinates) -> Unit,
    content: @Composable () -> Unit,
) {
    val top = parent?.top ?: 0
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        Layout(
            content = {
                Box(
                    Modifier
                        .pointerInput(Unit) { awaitEachGesture { awaitPointerEvent() } }
                        .testTag(TAG_POPUP)
                        .onGloballyPositioned(onPositioned),
                ) { content() }
            },
            modifier = Modifier.matchParentSize(),
        ) { measurables, constraints ->
            val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = STRIP_WIDTH.roundToPx()
                val y = (anchorY - top).toInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.place(x, y)
            }
        }
    }
}

@Composable
private fun BoxScope.ShowIndicator(
    ui: UiRefresh,
    onShow: () -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit,
) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .size(INDICATOR_TARGET)
            .testTag(TAG_INDICATOR)
            .onGloballyPositioned(onPositioned)
            .semantics { contentDescription = SHOW_TOOLS_LABEL }
            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                ui.chromeChanged()
                onShow()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(INDICATOR_VISUAL).border(BorderStroke(2.dp, Color.Black)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { ToolIcon(IconKind.SHOW, Modifier.size(24.dp)) }
    }
}
