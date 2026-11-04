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
import androidx.compose.runtime.remember
import io.github.szymonrychu.booxsketch.board.BoardScreen
import io.github.szymonrychu.booxsketch.documents.DocumentHost
import io.github.szymonrychu.booxsketch.ink.CountedPause
import io.github.szymonrychu.booxsketch.onyx.Epd
import io.github.szymonrychu.booxsketch.pairing.PairingScreen
import io.github.szymonrychu.booxsketch.send.SendOutcomeDialog
import io.github.szymonrychu.booxsketch.ui.AppShell
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import io.github.szymonrychu.booxsketch.ui.PanelShell
import io.github.szymonrychu.booxsketch.ui.PauseInputWhile
import io.github.szymonrychu.booxsketch.ui.ShellRefresh
import io.github.szymonrychu.booxsketch.ui.rememberShellState

/**
 * Hosts the tabbed app shell: the Drawing tab is the board (with its input) and the side panel over it.
 * A document opened from the Documents panel tab replaces the shell full screen until it is closed.
 */
class MainActivity : ComponentActivity() {
    // A denial only loses the background "pair again" notification; uploads work either way.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Asked on a send, not at launch: a dialog at launch leaves the activity paused. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
                val input = remember { CountedPause(container.inputSelector.select()) }
                val sendMessage by container.sendMessage.collectAsState()
                PauseInputWhile(sendMessage != null, input)
                sendMessage?.let { SendOutcomeDialog(it, container::dismissSendMessage) }
                // Hoisted so the shell (panel shown, its tab, chrome) survives a document opened full screen and closed.
                val shell = rememberShellState()
                val openDoc by container.openDocument.collectAsState()
                val doc = openDoc
                if (doc != null) {
                    DocumentHost(container, doc, input)
                } else {
                    AppShell(
                        tabs = container.tabs,
                        board = { chrome ->
                            BoardScreen(
                                vm = container.boardViewModel,
                                input = input,
                                setEraser = container::setEraser,
                                onSend = { all, caption ->
                                    askNotificationPermission()
                                    container.onSend(all, caption)
                                },
                                chrome = chrome,
                                panel = { PanelShell() },
                            )
                        },
                        state = shell,
                        refresh = ShellRefresh { Epd.fullRefresh(it) },
                    )
                }
            }
        }
    }
}
