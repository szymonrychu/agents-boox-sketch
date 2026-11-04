package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.onyx.Epd

internal const val EMPTY_PANEL_TEXT = "Nothing here yet"
internal const val TAG_CONVERSATION = "panel_conversation"
internal const val TAG_TAB_PREFIX = "panel_tab_"

/**
 * The panel: a conversation title button, one button per registered tab and the selected tab's
 * content. Tab changes trigger a full e-ink refresh to clear ghosting.
 */
@Composable
fun PanelShell(
    modifier: Modifier = Modifier,
    title: String = "Conversations",
) {
    val tabs = PanelTabs.tabs
    val selectedId = PanelTabs.selectedId
    val selected = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()
    val view = LocalView.current
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected?.id, tabs.size) {
        if (first) first = false else Epd.fullRefresh(view)
    }
    Column(modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { PanelTabs.onOpenConversations() },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_CONVERSATION),
            ) { Text(title, color = Color.Black) }
            tabs.forEach { tab ->
                val on = tab.id == selected?.id
                TextButton(
                    onClick = { PanelTabs.select(tab.id) },
                    modifier =
                        Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .background(if (on) Color.Black else Color.White)
                            .border(1.dp, Color.Black)
                            .testTag(TAG_TAB_PREFIX + tab.id),
                ) { Text(tab.title, color = if (on) Color.White else Color.Black) }
            }
        }
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            if (selected == null) {
                Text(EMPTY_PANEL_TEXT, Modifier.align(Alignment.Center), color = Color.Black)
            } else {
                selected.content()
            }
        }
    }
}

/**
 * Pauses [input] while [open] is true (a dialog or sheet covers the board) and resumes it when
 * closed or when this composable leaves the composition.
 */
@Composable
fun PauseInputWhile(
    open: Boolean,
    input: StrokeInput,
) {
    DisposableEffect(open, input) {
        if (open) input.pause()
        onDispose { if (open) input.resume() }
    }
}
