package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import io.github.szymonrychu.booxsketch.onyx.Epd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/** Share of the screen given to the board while the panel is shown, and whether the panel is hidden. */
data class SplitState(
    val ratio: Float = DEFAULT_RATIO,
    val collapsed: Boolean = false,
) {
    /** [ratio] clamped to the draggable range. */
    fun clamped() = copy(ratio = ratio.coerceIn(MIN_RATIO, MAX_RATIO))

    companion object {
        const val MIN_RATIO = 0.3f
        const val MAX_RATIO = 0.8f
        const val DEFAULT_RATIO = 0.6f
    }
}

/** Persists [SplitState] in a Preferences DataStore file. */
class SplitStateStore(
    private val store: DataStore<Preferences>,
) {
    val state: Flow<SplitState> =
        store.data.map { SplitState(it[RATIO] ?: SplitState.DEFAULT_RATIO, it[COLLAPSED] ?: false).clamped() }

    suspend fun load(): SplitState = state.first()

    suspend fun save(state: SplitState) {
        val s = state.clamped()
        store.edit {
            it[RATIO] = s.ratio
            it[COLLAPSED] = s.collapsed
        }
    }

    companion object {
        private val RATIO = floatPreferencesKey("split_ratio")
        private val COLLAPSED = booleanPreferencesKey("split_collapsed")

        fun create(
            scope: CoroutineScope,
            file: File,
        ) = SplitStateStore(PreferenceDataStoreFactory.create(scope = scope) { file })
    }
}

internal val DIVIDER_THICKNESS = 48.dp
internal const val TAG_DIVIDER = "split_divider"
internal const val TAG_COLLAPSE = "split_collapse"
internal const val TAG_PANEL = "split_panel"
internal const val COLLAPSE_LABEL = "Hide panel"
internal const val EXPAND_LABEL = "Show panel"

/**
 * Board and panel side by side (landscape, board left) or stacked (portrait, board top), with a
 * 48 dp divider strip between them. Dragging the strip changes the ratio within
 * [SplitState.MIN_RATIO]..[SplitState.MAX_RATIO]; its button collapses the panel so the board
 * takes the whole screen but the strip. The board slot never changes composition position, so the
 * board view survives a collapse. The board view limits itself to its own bounds, which exclude
 * the divider and the panel.
 */
@Composable
fun SplitLayout(
    board: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    state: SplitState,
    onStateChange: (SplitState) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.White)) {
        val landscape = maxWidth > maxHeight
        val total = with(LocalDensity.current) { (if (landscape) maxWidth else maxHeight).toPx() }
        val view = LocalView.current
        var live by remember(state.ratio) { mutableFloatStateOf(state.ratio) }
        val current by rememberUpdatedState(state)
        val settle = { Epd.fullRefresh(view) }
        var first by remember { mutableStateOf(true) }
        LaunchedEffect(state.collapsed) {
            if (first) first = false else settle()
        }
        val dividerPx = with(LocalDensity.current) { DIVIDER_THICKNESS.toPx() }
        val drag =
            Modifier.pointerInput(landscape, total) {
                detectDragGestures(
                    onDragEnd = {
                        onStateChange(current.copy(ratio = live).clamped())
                        settle()
                    },
                ) { change, amount ->
                    change.consume()
                    val usable = (total - dividerPx).coerceAtLeast(1f)
                    live = (live + (if (landscape) amount.x else amount.y) / usable).coerceIn(SplitState.MIN_RATIO, SplitState.MAX_RATIO)
                }
            }
        val panelShown = !state.collapsed
        val strip: @Composable () -> Unit = {
            DividerStrip(landscape, state.collapsed, if (panelShown) drag else Modifier) {
                onStateChange(state.copy(collapsed = !state.collapsed))
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(if (panelShown) live else 1f).fillMaxHeight()) { board() }
                strip()
                if (panelShown) Box(Modifier.weight(1f - live).fillMaxHeight().testTag(TAG_PANEL)) { panel() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(if (panelShown) live else 1f).fillMaxWidth()) { board() }
                strip()
                if (panelShown) Box(Modifier.weight(1f - live).fillMaxWidth().testTag(TAG_PANEL)) { panel() }
            }
        }
    }
}

@Composable
private fun DividerStrip(
    landscape: Boolean,
    collapsed: Boolean,
    dragModifier: Modifier,
    onToggle: () -> Unit,
) {
    val size = if (landscape) Modifier.width(DIVIDER_THICKNESS).fillMaxHeight() else Modifier.height(DIVIDER_THICKNESS).fillMaxWidth()
    Box(size.background(Color.White).then(dragModifier).testTag(TAG_DIVIDER), contentAlignment = Alignment.Center) {
        val line = if (landscape) Modifier.width(2.dp).fillMaxHeight() else Modifier.height(2.dp).fillMaxWidth()
        Box(line.background(Color.Black))
        TextButton(
            onClick = onToggle,
            modifier =
                Modifier
                    .size(DIVIDER_THICKNESS)
                    .background(Color.White)
                    .semantics { contentDescription = if (collapsed) EXPAND_LABEL else COLLAPSE_LABEL }
                    .testTag(TAG_COLLAPSE),
        ) {
            val glyph =
                when {
                    landscape -> if (collapsed) "<" else ">"
                    else -> if (collapsed) "^" else "v"
                }
            Text(glyph, color = Color.Black)
        }
    }
}
