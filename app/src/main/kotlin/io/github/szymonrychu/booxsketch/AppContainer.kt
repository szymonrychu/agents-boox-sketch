package io.github.szymonrychu.booxsketch

import android.app.Application
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.board.BoardViewModel
import io.github.szymonrychu.booxsketch.board.NotebookStore
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputSettings
import io.github.szymonrychu.booxsketch.ink.OnyxDiagnostics
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.onyx.OnyxProbe
import io.github.szymonrychu.booxsketch.onyx.OnyxStrokeInput
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import io.github.szymonrychu.booxsketch.pairing.AndroidKeystoreKeyProvider
import io.github.szymonrychu.booxsketch.pairing.KeyProvider
import io.github.szymonrychu.booxsketch.pairing.PairingRepository
import io.github.szymonrychu.booxsketch.pairing.TokenStore
import io.github.szymonrychu.booxsketch.ui.SplitStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Process-wide singletons, created once in [App.onCreate]. Later features add exactly one line
 * to [registerFeatures] (a panel tab, a worker, a handler) and their own manifest entries.
 */
class AppContainer(
    private val app: Application,
    private val dir: File = app.filesDir,
    private val probe: () -> ProbeResult = OnyxProbe::available,
    private val onyxFactory: () -> StrokeInput = { OnyxStrokeInput(app) },
    private val keyProvider: KeyProvider = AndroidKeystoreKeyProvider(),
    private val httpFactory: () -> OkHttpClient = { OkHttpClient() },
    /** Runs after a successful (re)pairing; Task 21 wires it to the outbox runner's resume. */
    val onRepaired: () -> Unit = {},
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: AppDatabase by lazy { AppDatabase.open(app) }
    val notebookStore: NotebookStore by lazy { NotebookStore(File(dir, "notebook.json")) }
    val okHttpClient: OkHttpClient by lazy { httpFactory() }

    /** Own IO scope, so DataStore file reads and writes never touch the main thread. */
    val splitStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val splitStateStore: SplitStateStore by lazy {
        SplitStateStore.create(splitStoreScope, File(dir, "datastore/split.preferences_pb"))
    }

    val tokenStore: TokenStore by lazy {
        TokenStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = splitStoreScope,
            ) { File(dir, "datastore/token.preferences_pb") },
            keyProvider,
            log = { Log.w(TAG, it) },
        )
    }
    val pairingRepository: PairingRepository by lazy {
        PairingRepository(tokenStore, { origin, token -> ConnectorClient(origin, token, okHttpClient) })
    }

    /**
     * Clears every Room table and the notebook, then the token last, so a crash midway cannot
     * leave data behind under a signed-out state. Called by Unpair.
     */
    suspend fun unpair() {
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            notebookStore.delete()
            tokenStore.clear()
        }
        boardViewModel.reset()
    }

    val boardViewModel: BoardViewModel by lazy { BoardViewModel(notebookStore, appScope, Dispatchers.IO) }

    /** The plain touch input; also the one the Eraser chip drives. */
    val touchInput: TouchStrokeInput by lazy { TouchStrokeInput(fingerEnabled = ::fingerEnabled) }

    @Volatile
    private var onyxProbed = false

    /** Finger drawing is on exactly when the Onyx pen path is not active, where palms would draw. */
    internal fun fingerEnabled(): Boolean = !(onyxProbed && _onyxFailure.value == null)

    private val _onyxFailure = MutableStateFlow<String?>(null)

    /** Why the Onyx path was abandoned, null while it works; shown by the diagnostics screen. */
    val onyxFailure: StateFlow<String?> = _onyxFailure

    @Suppress("TooGenericExceptionCaught")
    val inputSelector: InputSelector by lazy {
        InputSelector(
            probe = {
                try {
                    probe().also { onyxProbed = it.available }
                } catch (e: Throwable) {
                    ProbeResult(false, "probe failed: $e")
                }
            },
            settings =
                object : InputSettings {
                    override val forceTouch = false
                },
            diagnostics =
                OnyxDiagnostics {
                    Log.w(TAG, it)
                    _onyxFailure.value = it
                },
            scope = appScope,
            onyxFactory = onyxFactory,
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

    private companion object {
        const val TAG = "BooxSketch"
    }
}
