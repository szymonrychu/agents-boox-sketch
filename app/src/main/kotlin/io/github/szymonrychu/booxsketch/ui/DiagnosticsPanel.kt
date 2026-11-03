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
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputStatus

/** The Diagnostics panel tab, fed by the process [selector]. */
@Composable
fun DiagnosticsTab(selector: InputSelector) {
    val status by selector.status.collectAsState()
    DiagnosticsPanel(status, selector::rawEnabled)
}

/**
 * Pen input diagnostics a user can read out from the device: the active input path, whether raw
 * drawing is enabled and why touch was chosen. [rawEnabled] is read when shown and on Refresh.
 */
@Composable
fun DiagnosticsPanel(
    status: InputStatus,
    rawEnabled: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableIntStateOf(0) }
    val raw = remember(tick, status) { rawEnabled() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Input path: ${status.path?.label ?: "not selected yet"}", color = Color.Black)
        Text("Raw drawing enabled: ${if (raw) "yes" else "no"}", color = Color.Black)
        Text("Fallback reason: ${status.fallbackReason ?: "none"}", color = Color.Black)
        TextButton(onClick = { tick++ }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("Refresh", color = Color.Black)
        }
    }
}
