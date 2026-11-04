package io.github.szymonrychu.booxsketch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One tab of the side panel; lower [order] comes first. */
data class PanelTab(
    val id: String,
    val title: String,
    val order: Int,
    val content: @Composable () -> Unit,
)

/**
 * Registry of the panel tabs. Features (Spec, Thread, Hand-off) register themselves from
 * `AppContainer`; registering an id again replaces the earlier tab.
 */
object PanelTabs {
    private var registered by mutableStateOf<List<PanelTab>>(emptyList())

    /** Registered tabs sorted by [PanelTab.order], then id. Reads are observed by Compose. */
    val tabs: List<PanelTab> get() = registered.sortedWith(compareBy({ it.order }, { it.id }))

    /** Id of the selected tab; null selects the first. Survives recomposition and tab registration. */
    var selectedId by mutableStateOf<String?>(null)
        private set

    fun select(id: String) {
        selectedId = id
    }

    /** Called by the conversation title button; the conversations feature sets it. */
    var onOpenConversations: () -> Unit = {}

    fun register(tab: PanelTab) {
        registered = registered.filterNot { it.id == tab.id } + tab
    }

    internal fun clearForTest() {
        registered = emptyList()
        selectedId = null
        onOpenConversations = {}
    }
}
