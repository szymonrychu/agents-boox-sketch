package io.github.szymonrychu.booxsketch.pairing

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

const val TAG_ORIGIN = "pairing_origin"
const val TAG_CODE = "pairing_code"
const val TAG_PAIR = "pairing_pair"
const val TAG_PAIRING_ERROR = "pairing_error"
const val TAG_UNPAIR = "pairing_unpair"
const val TAG_PAIRED_ORIGIN = "pairing_paired_origin"
const val TAG_KEY_CLEAR = "pairing_key_clear"
const val TAG_KEY_BACKSPACE = "pairing_key_backspace"

/** Length of the pairing code shown on the Devices page. */
const val CODE_LENGTH = 6

/** Tag of the keypad key for [digit]. */
fun keyTag(digit: Char) = "pairing_key_$digit"

/** Tag of the code box at [index]. */
fun boxTag(index: Int) = "pairing_box_$index"

/** Text shown for a failed [PairResult]; null for success. */
fun pairingErrorText(result: PairResult): String? =
    when (result) {
        is PairResult.Ok -> {
            null
        }

        PairResult.InvalidOrigin -> {
            "Enter the server address, such as connector.example or https://connector.example (no path)"
        }

        PairResult.CodeRejected -> {
            "Code not valid or expired. Check the digits or make a new code on the Devices page " +
                "(a code is locked after too many wrong attempts)"
        }

        is PairResult.RateLimited -> {
            if (result.retryAfterS != null) "Too many attempts, wait ${result.retryAfterS} s" else "Too many attempts, wait a minute"
        }

        is PairResult.StorageFailed -> {
            "Cannot store the token on this device: ${result.message}"
        }

        is PairResult.Unreachable -> {
            "Cannot reach the server: ${result.message}"
        }
    }

/** Full-screen gate shown while no token is stored. [onPaired] runs after a successful pair. */
@Composable
fun PairingScreen(
    pair: suspend (origin: String, code: String) -> PairResult,
    onPaired: () -> Unit,
    initialOrigin: String = "",
) {
    var origin by remember { mutableStateOf(initialOrigin) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PairingContent(
        origin = origin,
        code = code,
        error = error,
        busy = busy,
        onOriginChange = { origin = it },
        onCodeChange = { code = it },
        onPair = {
            busy = true
            error = null
            scope.launch {
                try {
                    val result = pair(origin, code)
                    error = pairingErrorText(result)
                    // A used or wrong code never works again: start the next attempt empty.
                    if (result !is PairResult.Ok) code = ""
                    if (result is PairResult.Ok) onPaired()
                } finally {
                    busy = false
                }
            }
        },
    )
}

@Composable
internal fun PairingContent(
    origin: String,
    code: String,
    error: String?,
    busy: Boolean,
    onOriginChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pair with your server")
        OutlinedTextField(
            value = origin,
            onValueChange = onOriginChange,
            label = { Text("Server origin") },
            placeholder = { Text("connector.example") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag(TAG_ORIGIN),
        )
        Text("Pairing code from the Devices page")
        CodeBoxes(code)
        CodeKeypad(
            enabled = !busy,
            onDigit = { if (code.length < CODE_LENGTH) onCodeChange(code + it) },
            onBackspace = { onCodeChange(code.dropLast(1)) },
            onClear = { onCodeChange("") },
        )
        if (error != null) Text(error, Modifier.testTag(TAG_PAIRING_ERROR))
        Button(
            onClick = onPair,
            enabled = !busy && origin.isNotBlank() && code.length == CODE_LENGTH,
            modifier = Modifier.testTag(TAG_PAIR),
        ) { Text(if (busy) "Pairing..." else "Pair") }
    }
}

/** Six large boxes, one digit each; empty boxes stay blank. No IME: the keypad feeds them. */
@Composable
private fun CodeBoxes(code: String) {
    Row(Modifier.testTag(TAG_CODE), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(CODE_LENGTH) { i ->
            Box(
                Modifier
                    .size(width = 56.dp, height = 72.dp)
                    .border(3.dp, Color.Black)
                    .semantics(mergeDescendants = true) {}
                    .testTag(boxTag(i)),
                contentAlignment = Alignment.Center,
            ) { Text(code.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.headlineMedium) }
        }
    }
}

private val KEY_ROWS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))

/** On-screen 3x4 numeric keypad (1-9, Clear, 0, Backspace): black on white, thick borders. */
@Composable
internal fun CodeKeypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KEY_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d -> Key(d, keyTag(d[0]), enabled) { onDigit(d[0]) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Clear", TAG_KEY_CLEAR, enabled, onClear)
            Key("0", keyTag('0'), enabled) { onDigit('0') }
            Key("Del", TAG_KEY_BACKSPACE, enabled, onBackspace)
        }
    }
}

@Composable
private fun Key(
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 96.dp, height = 72.dp)
            .border(3.dp, Color.Black)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

/** Shows the paired origin and the Unpair button. Hosted by a later tab task, not yet by anything. */
@Composable
fun PairingSettings(
    origin: String?,
    onUnpair: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paired with")
        Text(origin ?: "-", Modifier.testTag(TAG_PAIRED_ORIGIN))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        onUnpair()
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.testTag(TAG_UNPAIR),
        ) { Text("Unpair") }
        Text("Unpairing removes the token and deletes all sketches and conversations on this device.")
    }
}
