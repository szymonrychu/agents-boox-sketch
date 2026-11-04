package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.onyx.Epd

/** Minimum touch target on the e-ink tablet. */
internal val MIN_TARGET = 48.dp

internal fun Modifier.minTarget(): Modifier = defaultMinSize(minWidth = MIN_TARGET, minHeight = MIN_TARGET)

/**
 * A small outlined label: black outline, never colour only. [selected] inverts it. With [onClick] it is a 48 dp target.
 */
@Composable
internal fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base =
        modifier
            .border(1.dp, Color.Black)
            .background(if (selected) Color.Black else Color.White)
    val sized = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick).minTarget() else base
    Box(sized.padding(horizontal = 8.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (selected) Color.White else Color.Black,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** Prev, the 1-based page number and Next; hidden while there is a single page. */
@Composable
internal fun Pager(
    index: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    if (!hasPrev && !hasNext) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrev, enabled = hasPrev, modifier = Modifier.minTarget().testTag(tagPrefix + "_prev")) { Text("Prev") }
        Text("${index + 1}")
        Button(onClick = onNext, enabled = hasNext, modifier = Modifier.minTarget().testTag(tagPrefix + "_next")) { Text("Next") }
    }
}

/** One segment of a segmented control: inverted when [on], never colour only (the selected state is in semantics too). */
@Composable
internal fun Segment(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .minTarget()
            .border(1.dp, Color.Black)
            .background(if (on) Color.Black else Color.White)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) Color.White else Color.Black) }
}

/** Marks a non-text control for tests and accessibility. */
internal fun Modifier.described(text: String): Modifier = semantics { contentDescription = text }

/** Clears e-ink ghosting with a full refresh whenever [keys] change (a page, a sub-tab, a screen), but not on first show. */
@Composable
internal fun RefreshOnChange(vararg keys: Any?) {
    val view = LocalView.current
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(*keys) {
        if (first) first = false else Epd.fullRefresh(view)
    }
}
