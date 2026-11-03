package io.github.szymonrychu.booxsketch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.szymonrychu.booxsketch.board.BoardScreen
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import io.github.szymonrychu.booxsketch.ui.PanelShell
import io.github.szymonrychu.booxsketch.ui.SplitLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Hosts the split layout: the board (with its input) beside the panel shell. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        // One small preferences read, so the first frame already has the saved split.
        val saved = runBlocking { container.splitStateStore.state.first() }
        setContent {
            EinkTheme {
                var split by remember { mutableStateOf(saved) }
                val scope = rememberCoroutineScope()
                val input = remember { container.inputSelector.select() }
                SplitLayout(
                    board = {
                        BoardScreen(
                            vm = container.boardViewModel,
                            input = input,
                            setEraser = container::setEraser,
                        )
                    },
                    panel = { PanelShell() },
                    state = split,
                    onStateChange = {
                        split = it
                        scope.launch { container.splitStateStore.save(it) }
                    },
                )
            }
        }
    }
}
