package io.github.szymonrychu.booxsketch.documents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.model.doc.splitColourMarkers

const val TAG_DOC_FEEDBACK_LATEST = "doc_feedback_latest"
const val TAG_DOC_FEEDBACK_TOGGLE = "doc_feedback_toggle"
const val TAG_DOC_FEEDBACK_PANEL = "doc_feedback_panel"
const val TAG_DOC_FEEDBACK_CLOSE = "doc_feedback_close"

fun feedbackItemTag(id: Int) = "doc_feedback_item_$id"

/** The label of a feedback kind: "Question", "Uncertain", "Illegible", "Note"; an unknown kind is capitalised as sent. */
fun feedbackKindLabel(kind: String): String = kind.replaceFirstChar { it.uppercase() }

/** [text] with its `[[colour]]` markers as bold labels in the ink colour (the name stays readable without colour). */
fun feedbackAnnotated(text: String): AnnotatedString =
    buildAnnotatedString {
        for (span in splitColourMarkers(text)) {
            val colour = span.colour
            if (colour == null) {
                append(span.text)
            } else {
                withStyle(SpanStyle(color = Color(colour.argb), fontWeight = FontWeight.Bold)) { append(span.text) }
            }
        }
    }

/** One line summary of the newest translator feedback; a tap opens the list. */
@Composable
fun FeedbackLatest(
    latest: FeedbackEntity,
    count: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, Color.Black))
            .clickable(onClick = onOpen)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TAG_DOC_FEEDBACK_LATEST),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(feedbackKindLabel(latest.kind) + ": ") }
                append(feedbackAnnotated(latest.text))
            },
            Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 1) Text("  +${count - 1}")
    }
}

/** The translator's feedback, newest first; each item shows its kind, where it applies and the text. */
@Composable
fun FeedbackPanel(
    items: List<FeedbackEntity>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Color.White).testTag(TAG_DOC_FEEDBACK_PANEL)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Translator notes (${items.size})", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            TextButton(onClick = onClose, modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_FEEDBACK_CLOSE)) {
                Text("Hide")
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            items(items, key = { it.id }) { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(BorderStroke(2.dp, Color.Black))
                        .padding(12.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag(feedbackItemTag(item.id)),
                ) {
                    val place =
                        item.whereText
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " ($it)" }
                            .orEmpty()
                    val version = item.specVersion?.let { " - v$it" }.orEmpty()
                    Text(feedbackKindLabel(item.kind) + place + version, fontWeight = FontWeight.Bold)
                    Text(feedbackAnnotated(item.text))
                }
            }
        }
    }
}
