package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val TAG_TAB_BAR = "tab_bar"

/** Test tag of one tab button: this prefix plus the [TabSpec.id]. */
internal const val TAG_TAB_ITEM_PREFIX = "tab_"
internal val TAB_BAR_HEIGHT = 48.dp

/**
 * E-ink tab bar: black on white, the selected tab inverted, no ripples or animations, 48 dp high.
 */
@Composable
fun TabBar(
    tabs: List<TabSpec>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        Box(
            modifier
                .fillMaxWidth()
                .height(TAB_BAR_HEIGHT)
                .background(Color.White)
                .testTag(TAG_TAB_BAR),
        ) {
            Row(
                Modifier.fillMaxWidth().height(TAB_BAR_HEIGHT).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val on = tab.id == selectedId
                    TextButton(
                        onClick = { onSelect(tab.id) },
                        modifier =
                            Modifier
                                .defaultMinSize(minHeight = TAB_BAR_HEIGHT)
                                .background(if (on) Color.Black else Color.White)
                                .border(1.dp, Color.Black)
                                .semantics { selected = on }
                                .testTag(TAG_TAB_ITEM_PREFIX + tab.id),
                    ) { Text(tab.title, color = if (on) Color.White else Color.Black) }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.Black),
            )
        }
    }
}
