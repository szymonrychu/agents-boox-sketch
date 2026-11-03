package io.github.szymonrychu.booxsketch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Id of the board (Drawings) tab; the shell hosts the board slot instead of its content. */
const val BOARD_TAB_ID = "board"

/** One top-level tab; lower [order] comes first. [content] fills the box given by its modifier. */
data class TabSpec(
    val id: String,
    val title: String,
    val order: Int,
    val content: @Composable (Modifier) -> Unit,
)

/** Registry of the top-level tabs. Features register themselves from `AppContainer`. */
class TabRegistry {
    private var registered by mutableStateOf<List<TabSpec>>(emptyList())

    /** Registered tabs sorted by order (stable). Reads are observed by Compose. */
    val tabs: List<TabSpec> get() = registered.sortedBy { it.order }

    /** Registers [spec]; an existing spec with the same id is replaced. */
    fun register(spec: TabSpec) {
        registered = registered.filterNot { it.id == spec.id } + spec
    }

    internal fun clearForTest() {
        registered = emptyList()
    }
}
