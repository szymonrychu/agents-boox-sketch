package io.github.szymonrychu.booxsketch.send

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

const val TAG_SEND_OUTCOME = "send_outcome"

private const val KIB = 1024

/** What the user is told about a send; null for a queued send (the thread shows its progress). */
fun sendOutcomeText(outcome: SendOutcome): String? =
    when (outcome) {
        is SendOutcome.Queued -> {
            null
        }

        SendOutcome.NothingToSend -> {
            "Nothing to send: no page changed since the last send. Long-press Send to send all pages."
        }

        is SendOutcome.TooManyRegions -> {
            "Not sent: page ${outcome.page + 1} has more than ${outcome.max} new image regions, " +
                "the server takes at most ${outcome.max} per page and send."
        }

        is SendOutcome.TooManyPages -> {
            "Not sent: ${outcome.count} pages selected, the server takes at most ${outcome.max} per send."
        }

        is SendOutcome.TooLarge -> {
            "Not sent: ${outcome.bytes / KIB} KiB is over the ${outcome.max / KIB} KiB limit per send. Send fewer pages."
        }

        is SendOutcome.StaleVersion -> {
            "A newer version exists; your notes stay with v${outcome.version}."
        }

        SendOutcome.AlreadySent -> {
            "These notes were already sent."
        }

        SendOutcome.NoBaseVersion -> {
            "Not sent: the edits need the document version they change. Wait for the document, then send again."
        }

        is SendOutcome.ExportFailed -> {
            "Not sent: page ${outcome.page + 1} cannot be exported (${outcome.reason})."
        }
    }

/** Shows a refused or failed send until dismissed. */
@Composable
fun SendOutcomeDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message, Modifier.testTag(TAG_SEND_OUTCOME)) },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
    )
}
