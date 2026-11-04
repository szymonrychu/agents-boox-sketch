package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.TaskDto

const val TAG_HISTORY_EMPTY = "history_empty"
const val TAG_HISTORY_PAGE_PREFIX = "history_page"
private const val HISTORY_PAGE_SIZE = 10

fun historyRowTag(id: Long) = "history_$id"

/** The events as "actor: kind" with the time, newest first, ten per page. */
@Composable
internal fun HistoryTab(task: TaskDto) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val events = task.events.asReversed()
    val last = ((events.size - 1) / HISTORY_PAGE_SIZE).coerceAtLeast(0)
    val page = index.coerceIn(0, last)
    RefreshOnChange(page)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (events.isEmpty()) Text("No history", Modifier.testTag(TAG_HISTORY_EMPTY))
        events.drop(page * HISTORY_PAGE_SIZE).take(HISTORY_PAGE_SIZE).forEach { e ->
            Column(Modifier.testTag(historyRowTag(e.id))) {
                Text("${e.actorSlug ?: e.actorKind}: ${e.kind.replace('_', ' ')}")
                Text(shortTime(e.at), style = MaterialTheme.typography.bodySmall)
            }
        }
        Pager(page, page > 0, page < last, { index = page - 1 }, { index = page + 1 }, TAG_HISTORY_PAGE_PREFIX)
    }
}
