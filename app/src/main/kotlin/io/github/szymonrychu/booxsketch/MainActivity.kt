package io.github.szymonrychu.booxsketch

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.github.szymonrychu.booxsketch.board.BoardScreen
import io.github.szymonrychu.booxsketch.board.BoardViewModel
import io.github.szymonrychu.booxsketch.board.NotebookStore
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputSettings
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.onyx.OnyxProbe
import io.github.szymonrychu.booxsketch.onyx.OnyxStrokeInput
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Hosts the full-screen board; Task 19 replaces this wiring with the split layout. */
class MainActivity : ComponentActivity() {
    private val vm by lazy {
        BoardViewModel(NotebookStore(File(filesDir, "notebook.json")), lifecycleScope, Dispatchers.IO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = selectInput()
        setContent { BoardScreen(vm, input) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun selectInput(): StrokeInput {
        // Finger drawing is only on when the pen path is not Onyx, where palms would draw.
        var onyx = false
        val touch = { TouchStrokeInput(fingerEnabled = { !onyx }) }
        val selector =
            InputSelector(
                probe = {
                    try {
                        OnyxProbe.available().also { onyx = it.available }
                    } catch (e: Throwable) {
                        ProbeResult(false, "probe failed: $e")
                    }
                },
                settings =
                    object : InputSettings {
                        override val forceTouch = false
                    },
                diagnostics = { Log.w(TAG, it) },
                scope = lifecycleScope,
                onyxFactory = { OnyxStrokeInput(this) },
                touchFactory = touch,
            )
        return try {
            selector.select()
        } catch (e: Exception) {
            Log.w(TAG, "input selection failed, using touch", e)
            onyx = false
            touch()
        } catch (e: LinkageError) {
            Log.w(TAG, "input selection failed, using touch", e)
            onyx = false
            touch()
        }
    }

    private companion object {
        const val TAG = "BooxSketch"
    }
}
