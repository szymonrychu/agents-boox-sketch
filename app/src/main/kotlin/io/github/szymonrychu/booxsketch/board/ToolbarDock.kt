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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
internal const val TAG_HIDE = "toolbar_hide"
internal const val TAG_INDICATOR = "toolbar_indicator"
internal const val SHOW_TOOLS_LABEL = "Show tools"
internal const val HIDE_TOOLS_LABEL = "Hide tools"
internal val INDICATOR_TARGET = 48.dp
internal val INDICATOR_VISUAL = 32.dp
internal val INDICATOR_MARGIN = 8.dp

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
 * Container around the colours-owned toolbar [toolbar]: it is shown or collapsed to a tap indicator,
 * and keeps the pen exclude rects in step. Every show or hide tells [ui] first (hold), then flips
 * the state. When shown, the dock rect is estimated from [rememberedDockSize] at the parent's
 * bottom edge until the measured rect arrives, so the first frame already excludes the dock.
 */
@Suppress("LongParameterList")
@Composable
fun BoxScope.ToolbarDock(
    visible: Boolean,
    onShow: () -> Unit,
    onHide: () -> Unit,
    ui: UiRefresh,
    onExcludeChange: (List<Rect>) -> Unit,
    rememberedDockSize: IntSize?,
    onDockSize: (IntSize) -> Unit,
    layoutEpoch: Int = 0,
    toolbar: @Composable () -> Unit,
) {
    var parent by remember { mutableStateOf<Rect?>(null) }
    // Keyed on visibility so a measurement never outlives the panel it was taken from.
    val measured = remember(visible) { mutableStateOf<Rect?>(null) }

    Box(Modifier.matchParentSize().onGloballyPositioned { parent = it.windowRect() })

    val estimate =
        remember(parent, rememberedDockSize) {
            val p = parent
            val s = rememberedDockSize
            if (p == null || s == null) null else Rect(p.left, p.bottom - s.height, p.right, p.bottom)
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

    if (visible) {
        DockPanel(
            ui = ui,
            onHide = onHide,
            onPositioned = {
                measured.value = it.windowRect()
                onDockSize(IntSize(it.size.width, it.size.height))
            },
            toolbar = toolbar,
        )
    } else {
        ShowIndicator(ui = ui, onShow = onShow, onPositioned = { measured.value = it.windowRect() })
    }
}

@Composable
private fun BoxScope.DockPanel(
    ui: UiRefresh,
    onHide: () -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit,
    toolbar: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Swallow every pointer event, so a tap between the toolbar's buttons never reaches the board.
                .pointerInput(Unit) { awaitEachGesture { awaitPointerEvent() } }
                .background(Color.White)
                .drawBehind { drawLine(Color.Black, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                .testTag(TAG_DOCK)
                .onGloballyPositioned(onPositioned),
        ) {
            TextButton(
                onClick = {
                    ui.chromeChanged()
                    onHide()
                },
                modifier = Modifier.align(Alignment.End).heightIn(min = INDICATOR_TARGET).testTag(TAG_HIDE),
            ) { Text("v", Modifier.semantics { contentDescription = HIDE_TOOLS_LABEL }) }
            toolbar()
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
            .align(Alignment.BottomEnd)
            .padding(INDICATOR_MARGIN)
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
        ) { Text("^", color = Color.Black) }
    }
}
