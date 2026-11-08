package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.ink.InkExperimentPrefs
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputStatus
import io.github.szymonrychu.booxsketch.ink.ShapeDragCounts
import io.github.szymonrychu.booxsketch.onyx.FirmwareBrushApi
import io.github.szymonrychu.booxsketch.onyx.InkDiag
import io.github.szymonrychu.booxsketch.onyx.InkExperiments

/** The Diagnostics panel tab, fed by the process [selector] and the persisted live ink [experiments]. */
@Composable
fun DiagnosticsTab(
    selector: InputSelector,
    experiments: InkExperimentPrefs,
) {
    val status by selector.status.collectAsState()
    val shapeDrags by selector.shapeDrags.collectAsState()
    val penFallbacks by InkDiag.fallbacks.collectAsState()
    val penOffReason by InkDiag.penOffReason.collectAsState()
    val firmwareBrush = remember { FirmwareBrushApi.status() }
    val firmwareDump = remember { FirmwareBrushApi.dump() }
    val inkExperiments by experiments.toggles.collectAsState()
    DiagnosticsPanel(
        status,
        selector::rawEnabled,
        shapeDrags = shapeDrags,
        penFallbacks = penFallbacks,
        penOffReason = penOffReason,
        firmwareBrush = firmwareBrush,
        firmwareDump = firmwareDump,
        inkExperiments = inkExperiments,
        onInkExperiments = experiments::set,
    )
}

/**
 * Pen input diagnostics a user can read out from the device: the active input path, whether raw
 * drawing is enabled and why touch was chosen. [rawEnabled] is read when shown and on Refresh.
 * [shapeDrags] counts shape drag events per source: on the device, a zero view count while the
 * window count grows means the raw layer claims the drag and only the window feed draws shapes.
 * [penFallbacks] counts committed strokes the SDK pens did not redraw (the app renderer drew them);
 * each one is logged under the InkDiag tag with its cause. [penOffReason] is why the SDK pens turned
 * themselves off ("SDK pens off: <exception class>: <message>"), shown only then. [firmwareBrush] says
 * whether the SDK found the firmware's stroke style and colour methods ([FirmwareBrushApi]); "missing"
 * means the live ink cannot follow the selected tool and colour. [firmwareDump] is the firmware pen API read by
 * reflection ([FirmwareBrushApi.dump]). [inkExperiments] are the live ink toggles (T-78), flipped one at a time
 * through [onInkExperiments]; they take effect at the next raw drawing open, enable or brush change.
 */
@Suppress("LongParameterList") // one optional parameter per diagnostic line
@Composable
fun DiagnosticsPanel(
    status: InputStatus,
    rawEnabled: () -> Boolean,
    modifier: Modifier = Modifier,
    shapeDrags: ShapeDragCounts = ShapeDragCounts(),
    penFallbacks: Int = 0,
    penOffReason: String? = null,
    firmwareBrush: String? = null,
    firmwareDump: String? = null,
    inkExperiments: InkExperiments? = null,
    onInkExperiments: (InkExperiments) -> Unit = {},
) {
    var tick by remember { mutableIntStateOf(0) }
    val raw = remember(tick, status) { rawEnabled() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Input path: ${status.path?.label ?: "not selected yet"}", color = Color.Black)
        Text("Raw drawing enabled: ${if (raw) "yes" else "no"}", color = Color.Black)
        Text("Fallback reason: ${status.fallbackReason ?: "none"}", color = Color.Black)
        Text("Shape drag events: window ${shapeDrags.window}, view ${shapeDrags.view}", color = Color.Black)
        Text("Pen redraw fallbacks: $penFallbacks", color = Color.Black)
        if (penOffReason != null) Text(penOffReason, color = Color.Black)
        if (firmwareBrush != null) Text("Firmware brush API: $firmwareBrush", color = Color.Black)
        if (firmwareDump != null) Text("Firmware API dump:\n$firmwareDump", color = Color.Black)
        if (inkExperiments != null) {
            ExperimentToggle("Vendor resume order", inkExperiments.vendorResumeOrder) {
                onInkExperiments(inkExperiments.copy(vendorResumeOrder = it))
            }
            ExperimentToggle("Region pen config", inkExperiments.regionPenConfig) {
                onInkExperiments(inkExperiments.copy(regionPenConfig = it))
            }
        }
        TextButton(onClick = { tick++ }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("Refresh", color = Color.Black)
        }
    }
}

@Composable
private fun ExperimentToggle(
    label: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    TextButton(onClick = { onChange(!on) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text("$label: ${if (on) "on" else "off"}", color = Color.Black)
    }
}
