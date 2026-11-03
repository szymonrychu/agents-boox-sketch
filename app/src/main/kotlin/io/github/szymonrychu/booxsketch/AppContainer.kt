package io.github.szymonrychu.booxsketch

import android.app.Application
import io.github.szymonrychu.booxsketch.board.BoardViewModel
import io.github.szymonrychu.booxsketch.board.NotebookStore
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputSettings
import io.github.szymonrychu.booxsketch.ink.OnyxDiagnostics
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.onyx.OnyxProbe
import io.github.szymonrychu.booxsketch.onyx.OnyxStrokeInput
import io.github.szymonrychu.booxsketch.ui.SplitStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File

/**
 * Process-wide singletons, created once in [App.onCreate]. Later features add exactly one line
 * to [registerFeatures] (a panel tab, a worker, a handler) and their own manifest entries.
 */
class AppContainer(
    private val app: Application,
    private val dir: File = app.filesDir,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: AppDatabase by lazy { AppDatabase.open(app) }
    val notebookStore: NotebookStore by lazy { NotebookStore(File(dir, "notebook.json")) }
    val okHttpClient: OkHttpClient by lazy { OkHttpClient() }
    val splitStateStore: SplitStateStore by lazy { SplitStateStore.create(appScope, File(dir, "datastore/split.preferences_pb")) }

    val boardViewModel: BoardViewModel by lazy { BoardViewModel(notebookStore, appScope, Dispatchers.IO) }

    /** The plain touch input; also the one the Eraser chip drives. */
    val touchInput: TouchStrokeInput by lazy { TouchStrokeInput() }

    private val _onyxFailure = MutableStateFlow<String?>(null)

    /** Why the Onyx path was abandoned, null while it works; shown by the diagnostics screen. */
    val onyxFailure: StateFlow<String?> = _onyxFailure

    val inputSelector: InputSelector by lazy {
        InputSelector(
            probe = OnyxProbe::available,
            settings =
                object : InputSettings {
                    override val forceTouch = false
                },
            diagnostics = OnyxDiagnostics { _onyxFailure.value = it },
            scope = appScope,
            onyxFactory = { OnyxStrokeInput(app) },
            touchFactory = { touchInput },
        )
    }

    /**
     * Mirrors the Eraser chip into the touch input. The Onyx raw path erases with the stylus
     * eraser end only, so it needs nothing.
     */
    fun setEraser(on: Boolean) {
        touchInput.eraserMode = on
    }

    init {
        registerFeatures()
    }

    private fun registerFeatures() {
        // One line per feature, added by its task.
    }
}
