package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.TaskDto

const val TAG_DETAIL_BACK = "detail_back"
const val TAG_DETAIL_NOT_FOUND = "detail_not_found"
const val TAG_DETAIL_STALE = "detail_stale"
const val TAG_DETAIL_ERROR = "detail_error"
const val TAG_DETAIL_LOADING = "detail_loading"
const val TAG_DETAIL_HEADER = "detail_header"

enum class SubTab(
    val label: String,
) {
    OVERVIEW("Overview"),
    DECISIONS("Decisions"),
    TODO("Todo"),
    COMMENTS("Comments"),
    HISTORY("History"),
}

fun subTabTag(tab: SubTab) = "detail_tab_${tab.name.lowercase()}"

/** The task header: key, title, status chip and the "Blocked (n)" chip. */
@Composable
internal fun TaskHeader(task: TaskDto) {
    Column(Modifier.testTag(TAG_DETAIL_HEADER), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(task.key, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Chip(statusText(task.status), Modifier.testTag(taskStatusTag(task.key)))
            if (task.openDecisions > 0) Chip("Blocked (${task.openDecisions})", Modifier.testTag(taskBlockedTag(task.key)))
        }
        Text(task.title, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * One task with the sub-tabs Overview, Decisions, Todo, Comments and History. [onFilterSession] is called with a
 * waiting session's id (Q8): the caller filters the list by it and goes back to it.
 */
@Composable
fun TaskDetailScreen(
    vm: TaskDetailViewModel,
    onBack: () -> Unit,
    onFilterSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(SubTab.OVERVIEW) }
    WhileStarted(vm) { vm.observe() }
    RefreshOnChange(tab, state.editingBody, state.notFound)
    val task = state.task
    when {
        state.notFound -> {
            NotFound(vm.key, onBack, modifier)
        }

        task == null -> {
            Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackButton(onBack)
                if (state.error !=
                    null
                ) {
                    Text(state.error.orEmpty(), Modifier.testTag(TAG_DETAIL_ERROR))
                } else {
                    Text("Loading", Modifier.testTag(TAG_DETAIL_LOADING))
                }
                if (state.error != null) TextButton(onClick = vm::reload, modifier = Modifier.minTarget()) { Text("Retry") }
            }
        }

        state.editingBody -> {
            BodyEditor(task.body, state.busy, state.error, vm::saveBody, vm::cancelEditBody, modifier)
        }

        else -> {
            DetailBody(task, state, vm, tab, { tab = it }, onBack, onFilterSession, modifier)
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DetailBody(
    task: TaskDto,
    state: TaskDetailState,
    vm: TaskDetailViewModel,
    tab: SubTab,
    onTab: (SubTab) -> Unit,
    onBack: () -> Unit,
    onFilterSession: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackButton(onBack)
        TaskHeader(task)
        if (state.staleBanner) Text(STALE_TEXT, Modifier.testTag(TAG_DETAIL_STALE))
        state.error?.let { Text(it, Modifier.testTag(TAG_DETAIL_ERROR)) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SubTab.entries.forEach { t -> Segment(t.label, t == tab, { onTab(t) }, Modifier.testTag(subTabTag(t))) }
        }
        when (tab) {
            SubTab.OVERVIEW -> OverviewTab(task, state, vm, onFilterSession)
            SubTab.DECISIONS -> DecisionsTab(task, state, vm::answer)
            SubTab.TODO -> TodoTab(task, state, vm)
            SubTab.COMMENTS -> CommentsTab(task, vm::addComment)
            SubTab.HISTORY -> HistoryTab(task)
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.minTarget().testTag(TAG_DETAIL_BACK)) { Text("Back") }
}

@Composable
private fun NotFound(
    key: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Task $key not found", Modifier.testTag(TAG_DETAIL_NOT_FOUND))
        Button(onClick = onBack, modifier = Modifier.minTarget().testTag(TAG_DETAIL_BACK)) { Text("Back") }
    }
}
