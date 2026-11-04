package io.github.szymonrychu.booxsketch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.szymonrychu.booxsketch.board.BoardScreen
import io.github.szymonrychu.booxsketch.pairing.PairingScreen
import io.github.szymonrychu.booxsketch.send.SendOutcomeDialog
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import io.github.szymonrychu.booxsketch.ui.PanelShell
import io.github.szymonrychu.booxsketch.ui.PauseInputWhile
import io.github.szymonrychu.booxsketch.ui.SplitLayout
import io.github.szymonrychu.booxsketch.ui.SplitState
import kotlinx.coroutines.launch

/** Hosts the split layout: the board (with its input) beside the panel shell. */
class MainActivity : ComponentActivity() {
    // A denial only loses the background "pair again" notification; uploads work either way.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as App).container
        setContent {
            EinkTheme {
                val loaded by container.tokenStore.loaded.collectAsState()
                val token by container.tokenStore.tokenFlow.collectAsState()
                val knownOrigin by container.tokenStore.originFlow.collectAsState()
                // Blank until the stored token is known, so a paired start never flashes the gate.
                if (!loaded) return@EinkTheme
                if (token == null) {
                    PairingScreen(
                        pair = container.pairingRepository::pair,
                        onPaired = container::repaired,
                        initialOrigin = knownOrigin.orEmpty(),
                    )
                    return@EinkTheme
                }
                // Read off the main thread; the default shows until the saved split arrives.
                val stored by container.splitStateStore.state.collectAsState(initial = SplitState())
                var edited by remember { mutableStateOf<SplitState?>(null) }
                val split = edited ?: stored
                val scope = rememberCoroutineScope()
                val input = remember { container.inputSelector.select() }
                val sendMessage by container.sendMessage.collectAsState()
                PauseInputWhile(sendMessage != null, input)
                sendMessage?.let { SendOutcomeDialog(it, container::dismissSendMessage) }
                SplitLayout(
                    board = {
                        BoardScreen(
                            vm = container.boardViewModel,
                            input = input,
                            setEraser = container::setEraser,
                            onSend = container::onSend,
                        )
                    },
                    panel = { PanelShell() },
                    state = split,
                    onStateChange = {
                        edited = it
                        scope.launch { container.splitStateStore.save(it) }
                    },
                )
            }
        }
    }
}
