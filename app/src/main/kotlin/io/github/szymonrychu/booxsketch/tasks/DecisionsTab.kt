package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.TaskDto

const val TAG_DECISIONS_EMPTY = "decisions_empty"

/** The decisions of the task, oldest first. */
@Composable
internal fun DecisionsTab(
    task: TaskDto,
    state: TaskDetailState,
    onAnswer: (decisionId: Long, choice: Int?, text: String?, note: String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (task.decisions.isEmpty()) Text("No decisions on this task", Modifier.testTag(TAG_DECISIONS_EMPTY))
        task.decisions.forEach { d ->
            DecisionCard(d, notice = state.decisionNotice[d.id], answering = d.id in state.answering, onAnswer = onAnswer)
        }
    }
}
