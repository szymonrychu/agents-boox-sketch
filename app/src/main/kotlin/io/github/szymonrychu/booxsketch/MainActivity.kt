package io.github.szymonrychu.booxsketch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.szymonrychu.booxsketch.board.BoardScreen
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import io.github.szymonrychu.booxsketch.ui.PanelShell
import io.github.szymonrychu.booxsketch.ui.SplitLayout
import io.github.szymonrychu.booxsketch.ui.SplitState
import kotlinx.coroutines.launch

/** Hosts the split layout: the board (with its input) beside the panel shell. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        setContent {
            EinkTheme {
                // Read off the main thread; the default shows until the saved split arrives.
                val stored by container.splitStateStore.state.collectAsState(initial = SplitState())
                var edited by remember { mutableStateOf<SplitState?>(null) }
                val split = edited ?: stored
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
                        edited = it
                        scope.launch { container.splitStateStore.save(it) }
                    },
                )
            }
        }
    }
}
