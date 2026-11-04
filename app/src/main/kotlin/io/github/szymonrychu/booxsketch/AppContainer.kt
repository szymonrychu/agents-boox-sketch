package io.github.szymonrychu.booxsketch

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.board.BoardViewModel
import io.github.szymonrychu.booxsketch.board.NotebookStore
import io.github.szymonrychu.booxsketch.conversations.CONVERSATIONS_TAB_ID
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.conversations.ConversationsTab
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.documents.AnnotatedInput
import io.github.szymonrychu.booxsketch.documents.AnnotationStore
import io.github.szymonrychu.booxsketch.documents.DOCUMENTS_TAB_ID
import io.github.szymonrychu.booxsketch.documents.DocumentInk
import io.github.szymonrychu.booxsketch.documents.DocumentLayout
import io.github.szymonrychu.booxsketch.documents.DocumentRow
import io.github.szymonrychu.booxsketch.documents.DocumentViewModel
import io.github.szymonrychu.booxsketch.documents.DocumentsTab
import io.github.szymonrychu.booxsketch.documents.DocumentsViewModel
import io.github.szymonrychu.booxsketch.documents.MarkdownPaginator
import io.github.szymonrychu.booxsketch.documents.MermaidRenderer
import io.github.szymonrychu.booxsketch.documents.SourcesViewModel
import io.github.szymonrychu.booxsketch.documents.SpecSync
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputSettings
import io.github.szymonrychu.booxsketch.ink.OnyxDiagnostics
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.onyx.OnyxProbe
import io.github.szymonrychu.booxsketch.onyx.OnyxStrokeInput
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import io.github.szymonrychu.booxsketch.pairing.AndroidKeystoreKeyProvider
import io.github.szymonrychu.booxsketch.pairing.KeyProvider
import io.github.szymonrychu.booxsketch.pairing.PairingRepository
import io.github.szymonrychu.booxsketch.pairing.TokenStore
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.render.PngExporter
import io.github.szymonrychu.booxsketch.send.ConfigCache
import io.github.szymonrychu.booxsketch.send.DrainSummary
import io.github.szymonrychu.booxsketch.send.OutboxRunner
import io.github.szymonrychu.booxsketch.send.OutboxScheduler
import io.github.szymonrychu.booxsketch.send.SendBoard
import io.github.szymonrychu.booxsketch.send.SendOutcome
import io.github.szymonrychu.booxsketch.send.SendUseCase
import io.github.szymonrychu.booxsketch.send.sendOutcomeText
import io.github.szymonrychu.booxsketch.ui.BOARD_TAB_ID
import io.github.szymonrychu.booxsketch.ui.DiagnosticsTab
import io.github.szymonrychu.booxsketch.ui.PanelTab
import io.github.szymonrychu.booxsketch.ui.PanelTabs
import io.github.szymonrychu.booxsketch.ui.TabRegistry
import io.github.szymonrychu.booxsketch.ui.TabSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import io.github.szymonrychu.booxsketch.documents.DOCUMENTS_TAB_ORDER as DOCUMENTS_PANEL_TAB_ORDER

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
    /** Extra hook run by [repaired] after a successful (re)pairing (tests count calls). */
    val onRepaired: () -> Unit = {},
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: AppDatabase by lazy { AppDatabase.open(app) }
    val notebookStore: NotebookStore by lazy { NotebookStore(File(dir, "notebook.json")) }
    val okHttpClient: OkHttpClient by lazy { httpFactory() }

    /** Own IO scope, so DataStore file reads and writes never touch the main thread. */
    val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tokenStore: TokenStore by lazy {
        TokenStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = storeScope,
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
            outboxRoot.deleteRecursively()
            annotationStore.deleteAll()
            notebookStore.delete()
            tokenStore.clear()
        }
        outboxScheduler.cancelAll()
        conversations.select(null)
        openDocument.value = null
        documentInk.forget()
        boardViewModel.reset()
    }

    val boardViewModel: BoardViewModel by lazy { BoardViewModel(notebookStore, appScope, Dispatchers.IO) }

    /** The plain touch input; also the one the Eraser chip drives. */
    val touchInput: TouchStrokeInput by lazy { TouchStrokeInput(fingerEnabled = ::fingerEnabled) }

    @Volatile
    private var onyxProbed = false

    /** Finger drawing is on exactly when the Onyx pen path is not active, where palms would draw. */
    internal fun fingerEnabled(): Boolean = openDocument.value == null && !(onyxProbed && _onyxFailure.value == null)

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

    // Send flow, outbox worker and conversations (Task 21).

    val outboxRoot = File(dir, "outbox")
    val configCache: ConfigCache by lazy { ConfigCache(File(dir, "connector-config.json")) }
    private val outboxScheduler: OutboxScheduler by lazy { OutboxScheduler(app) }

    /** A client for the paired origin and token; null when not paired. Waits for the stored token. */
    suspend fun connector(): ConnectorClient? {
        tokenStore.loaded.first { it }
        val origin = tokenStore.originFlow.value ?: return null
        if (tokenStore.token() == null) return null
        return ConnectorClient(origin, tokenStore::token, okHttpClient)
    }

    /** Frozen document pages and ink, per conversation and spec version. */
    val annotationStore: AnnotationStore by lazy { AnnotationStore(dir) }

    val conversations: ConversationRepository by lazy {
        ConversationRepository(
            database,
            ::connector,
            app.getSharedPreferences("conversations", Context.MODE_PRIVATE),
            outboxRoot,
            onUnauthorized = pairingRepository::rejectToken,
            afterRefresh = { specSync.sync() },
            annotations = annotationStore,
        )
    }

    /** The document the user opened from the Documents tab; null shows the board and panel. */
    val openDocument = MutableStateFlow<DocumentRow?>(null)

    val documentsViewModel: DocumentsViewModel by lazy { DocumentsViewModel(database, appScope) { openDocument.value = it } }

    /** Every document page is laid out at the notebook canvas size, in both orientations. */
    val documentLayout = DocumentLayout(BoardViewModel.DEFAULT_CANVAS.w, BoardViewModel.DEFAULT_CANVAS.h)

    private val mermaid: MermaidRenderer by lazy { MermaidRenderer(app) }

    /** A view model for one document; pages come from the markdown paginator on a background thread. */
    fun documentViewModel(row: DocumentRow): DocumentViewModel =
        DocumentViewModel(
            conversationLocalId = row.conversationLocalId,
            serverId = row.serverId,
            db = database,
            paginate = { markdown -> withContext(Dispatchers.Default) { MarkdownPaginator(documentLayout, mermaid).paginate(markdown) } },
            store = annotationStore,
            layout = documentLayout,
            scope = appScope,
        )

    /** The ink of documents, one notebook per conversation and version. */
    val documentInk: DocumentInk by lazy { DocumentInk(annotationStore, appScope) }

    /** The sketches spec [version] of [serverId] was made from. */
    fun sourcesViewModel(
        serverId: String,
        version: Int,
    ): SourcesViewModel = SourcesViewModel(serverId, version, database, ::connector, File(app.cacheDir, "sources"), appScope)

    /** Copies spec versions into Room: after a conversations refresh and while the Documents tab is shown. */
    val specSync: SpecSync by lazy { SpecSync(database, ::connector, { conversations.latestSpecVersions }) }

    /** A 401 pauses the queue (never fails items) and drops the token, so the gate shows Pairing. */
    val outboxRunner: OutboxRunner by lazy {
        OutboxRunner(
            database,
            ::connector,
            onNeedsPairing = pairingRepository::rejectToken,
            schedule = outboxScheduler::later,
            annotations = annotationStore,
        )
    }

    val sendUseCase: SendUseCase by lazy {
        SendUseCase(
            db = database,
            conversations = conversations,
            board =
                object : SendBoard {
                    override fun notebook() = boardViewModel.notebook.value

                    override suspend fun markSent(sent: Map<Int, Page>) =
                        withContext(Dispatchers.Main.immediate) { boardViewModel.markSentIfUnchanged(sent) }
                },
            export = PngExporter(CanvasStrokeRenderer())::export,
            limits = configCache::current,
            outboxRoot = outboxRoot,
            kick = ::kickOutbox,
            annotations = annotationStore,
        )
    }

    private val _sendMessage = MutableStateFlow<String?>(null)

    /** Why the last send was refused or failed, until [dismissSendMessage]; null after a queued send. */
    val sendMessage: StateFlow<String?> = _sendMessage

    /** The board's Send (tap: changed pages, long-press: all pages). */
    fun onSend(
        all: Boolean,
        caption: String?,
    ) {
        appScope.launch { _sendMessage.value = runSend(all, caption) }
    }

    /**
     * Any failure of the send (disk, Room, export) becomes a message: an exception escaping into
     * [appScope] would crash the app. Cancellation still propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun runSend(
        all: Boolean,
        caption: String?,
        send: suspend (Boolean, String?) -> SendOutcome = sendUseCase::send,
    ): String? =
        try {
            sendOutcomeText(send(all, caption))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send failed: $e")
            "Not sent: the sketch could not be saved (${e.message ?: e.javaClass.simpleName})."
        }

    /** Sends the ink of the open document draft; a refusal or failure shows in the send dialog, the draft then turns read-only. */
    @Suppress("TooGenericExceptionCaught")
    fun onSendNotes(
        vm: DocumentViewModel,
        caption: String?,
    ) {
        appScope.launch {
            val state = vm.state.value
            val canvas = Canvas(documentLayout.widthPx, documentLayout.pageHeightPx, DOCUMENT_DPI)
            val notebook = documentInk.board(vm.conversationLocalId, state.version, state.pages.size, canvas).notebook.value
            val inputs =
                state.pages.mapIndexed { i, p ->
                    AnnotatedInput(i, p.file, notebook.pages.getOrNull(i) ?: Page(emptyList(), false), docPage = i, lines = p.sourceLines)
                }
            _sendMessage.value =
                try {
                    sendOutcomeText(sendUseCase.sendAnnotation(vm.conversationLocalId, state.version, state.latestVersion, inputs, caption))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "send notes failed: $e")
                    "Not sent: the notes could not be saved (${e.message ?: e.javaClass.simpleName})."
                }
            vm.refreshReadOnly()
        }
    }

    fun dismissSendMessage() {
        _sendMessage.value = null
    }

    /** Refreshes the connector limits once per process, then sends every due outbox item. */
    suspend fun drainOutbox(): DrainSummary {
        if (!configCache.fetched) connector()?.let { configCache.refresh(it) }
        return outboxRunner.drain()
    }

    /** Schedules the `outbox` work and, as the app is in the foreground, drains right away. */
    fun kickOutbox() {
        outboxScheduler.kick()
        appScope.launch { drainOutbox() }
    }

    /** Called by the pairing gate after a successful (re)pairing: paused uploads resume. */
    fun repaired() {
        appScope.launch {
            outboxRunner.resumeAfterPairing()
            kickOutbox()
        }
        onRepaired()
    }

    init {
        registerFeatures()
    }

    /** Top-level tabs of the app shell; the Drawing tab hosts the board. */
    val tabs = TabRegistry()

    private fun registerFeatures() {
        // One line per feature, added by its task.
        tabs.register(TabSpec(BOARD_TAB_ID, "Drawing", order = DRAWING_TAB_ORDER) { })
        // wave B: Tasks tab, wave C: Documents tab register here (Documents is a panel tab until then)
        PanelTabs.register(PanelTab("diagnostics", "Diagnostics", DIAGNOSTICS_ORDER) { DiagnosticsTab(inputSelector) })
        PanelTabs.register(PanelTab(CONVERSATIONS_TAB_ID, "Conversations", order = 0) { ConversationsTab() })
        PanelTabs.register(PanelTab(DOCUMENTS_TAB_ID, "Documents", DOCUMENTS_PANEL_TAB_ORDER) { DocumentsTab() })
        PanelTabs.onOpenConversations = { PanelTabs.select(CONVERSATIONS_TAB_ID) }
    }

    companion object {
        private const val TAG = "BooxSketch"
        private const val DOCUMENT_DPI = 300f
        private const val DIAGNOSTICS_ORDER = 900
        const val DRAWING_TAB_ORDER = 10
        const val TASKS_TAB_ORDER = 20
        const val DOCUMENTS_TAB_ORDER = 30
    }
}
