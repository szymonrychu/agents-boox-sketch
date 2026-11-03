package io.github.szymonrychu.booxsketch.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

const val TAG_ORIGIN = "pairing_origin"
const val TAG_TOKEN = "pairing_token"
const val TAG_PAIR = "pairing_pair"
const val TAG_PAIRING_ERROR = "pairing_error"
const val TAG_UNPAIR = "pairing_unpair"
const val TAG_PAIRED_ORIGIN = "pairing_paired_origin"

/** Text shown for a failed [PairResult]; null for success. */
fun pairingErrorText(result: PairResult): String? =
    when (result) {
        is PairResult.Ok -> null
        PairResult.InvalidOrigin -> "Enter an https:// origin without a path, such as https://connector.example"
        PairResult.TokenRejected -> "The server rejected this token"
        is PairResult.Unreachable -> "Cannot reach the server: ${result.message}"
    }

/** Full-screen gate shown while no token is stored. [onPaired] runs after a successful pair. */
@Composable
fun PairingScreen(
    pair: suspend (origin: String, token: String) -> PairResult,
    onPaired: () -> Unit,
) {
    var origin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PairingContent(
        origin = origin,
        token = token,
        error = error,
        busy = busy,
        onOriginChange = { origin = it },
        onTokenChange = { token = it },
        onPair = {
            busy = true
            error = null
            scope.launch {
                val result = pair(origin, token)
                busy = false
                error = pairingErrorText(result)
                if (result is PairResult.Ok) onPaired()
            }
        },
    )
}

@Composable
internal fun PairingContent(
    origin: String,
    token: String,
    error: String?,
    busy: Boolean,
    onOriginChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPair: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text("Pair with your server")
        OutlinedTextField(
            value = origin,
            onValueChange = onOriginChange,
            label = { Text("Server origin") },
            placeholder = { Text("https://connector.example") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag(TAG_ORIGIN),
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            label = { Text("Device token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag(TAG_TOKEN),
        )
        if (error != null) Text(error, Modifier.testTag(TAG_PAIRING_ERROR))
        Button(
            onClick = onPair,
            enabled = !busy && origin.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.testTag(TAG_PAIR),
        ) { Text(if (busy) "Pairing..." else "Pair") }
    }
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
                    onUnpair()
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.testTag(TAG_UNPAIR),
        ) { Text("Unpair") }
        Text("Unpairing removes the token and deletes all sketches and conversations on this device.")
    }
}
