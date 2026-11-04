package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.api.DecisionDto

fun decisionCardTag(id: Long) = "decision_$id"

fun decisionOptionTag(
    id: Long,
    index: Int,
) = "decision_${id}_option_$index"

fun decisionTextTag(id: Long) = "decision_${id}_text"

fun decisionNoteTag(id: Long) = "decision_${id}_note"

fun decisionSubmitTag(id: Long) = "decision_${id}_submit"

fun decisionNoticeTag(id: Long) = "decision_${id}_notice"

fun decisionAnswerTag(id: Long) = "decision_${id}_answer"

fun decisionWithdrawnTag(id: Long) = "decision_${id}_withdrawn"

/** One decision: the question with its context, then the answer form, the stored answer or "Withdrawn". */
@Composable
internal fun DecisionCard(
    decision: DecisionDto,
    notice: String?,
    answering: Boolean,
    onAnswer: (decisionId: Long, choice: Int?, text: String?, note: String?) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Black)
            .padding(12.dp)
            .testTag(decisionCardTag(decision.id)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(decision.question, fontWeight = FontWeight.Bold)
        decision.askingSlug?.let { Text("Asked by $it", style = MaterialTheme.typography.bodySmall) }
        if (decision.context.isNotBlank()) Text(decision.context)
        notice?.let { Text(it, Modifier.testTag(decisionNoticeTag(decision.id))) }
        when (decision.status) {
            "open" -> {
                OpenDecision(decision, answering, onAnswer)
            }

            "withdrawn" -> {
                Text("Withdrawn", Modifier.testTag(decisionWithdrawnTag(decision.id)))
            }

            else -> {
                val answer = decision.answer
                Text("Answer: ${answer?.label ?: answer?.text.orEmpty()}", Modifier.testTag(decisionAnswerTag(decision.id)))
                decision.note?.takeIf { it.isNotBlank() }?.let { Text("Note: $it") }
            }
        }
    }
}

@Composable
private fun OpenDecision(
    decision: DecisionDto,
    answering: Boolean,
    onAnswer: (Long, Int?, String?, String?) -> Unit,
) {
    var choice by rememberSaveable(decision.id) { mutableStateOf<Int?>(null) }
    var text by rememberSaveable(decision.id) { mutableStateOf("") }
    var note by rememberSaveable(decision.id) { mutableStateOf("") }
    decision.options.forEachIndexed { i, option ->
        val on = choice == i
        Row(
            Modifier
                .fillMaxWidth()
                .minTarget()
                .border(if (on) 3.dp else 1.dp, Color.Black)
                .clickable(role = Role.RadioButton) {
                    choice = i
                    text = ""
                }.semantics { selected = on }
                .padding(8.dp)
                .testTag(decisionOptionTag(decision.id, i)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (on) "(x)" else "( )")
            Column(Modifier.weight(1f)) {
                Text(option.label, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                option.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (option.recommended) Chip("Recommended")
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isNotEmpty()) choice = null
        },
        label = { Text("Own answer") },
        modifier = Modifier.fillMaxWidth().testTag(decisionTextTag(decision.id)),
    )
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text("Note") },
        modifier = Modifier.fillMaxWidth().testTag(decisionNoteTag(decision.id)),
    )
    Button(
        onClick = { onAnswer(decision.id, choice, if (choice == null) text else null, note.ifBlank { null }) },
        enabled = !answering && (choice != null || text.isNotBlank()),
        modifier = Modifier.minTarget().testTag(decisionSubmitTag(decision.id)),
    ) { Text(if (answering) "Sending" else "Submit") }
}
