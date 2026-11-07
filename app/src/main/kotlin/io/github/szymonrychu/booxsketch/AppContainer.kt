package io.github.szymonrychu.booxsketch

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.TaskEvents
import io.github.szymonrychu.booxsketch.api.TasksClient
import io.github.szymonrychu.booxsketch.board.BoardViewModel
import io.github.szymonrychu.booxsketch.board.NotebookFiles
import io.github.szymonrychu.booxsketch.board.NotebookStore
import io.github.szymonrychu.booxsketch.board.NotebookSync
import io.github.szymonrychu.booxsketch.board.StrikePreviewSync
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
import io.github.szymonrychu.booxsketch.documents.FeedbackSync
import io.github.szymonrychu.booxsketch.documents.MarkdownPaginator
import io.github.szymonrychu.booxsketch.documents.MermaidRenderer
import io.github.szymonrychu.booxsketch.documents.ShareBundle
import io.github.szymonrychu.booxsketch.documents.SourcesViewModel
import io.github.szymonrychu.booxsketch.documents.SpecImageCache
import io.github.szymonrychu.booxsketch.documents.SpecSync
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.InputSettings
import io.github.szymonrychu.booxsketch.ink.OnyxDiagnostics
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.ink.TouchStrokeInput
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.notebook.PendingOp
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
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
import io.github.szymonrychu.booxsketch.tasks.TASKS_TAB_ID
import io.github.szymonrychu.booxsketch.tasks.TasksController
import io.github.szymonrychu.booxsketch.tasks.TasksTab
import io.github.szymonrychu.booxsketch.ui.BOARD_TAB_ID
import io.github.szymonrychu.booxsketch.ui.DiagnosticsTab
import io.github.szymonrychu.booxsketch.ui.PanelTab
import io.github.szymonrychu.booxsketch.ui.PanelTabs
import io.github.szymonrychu.booxsketch.ui.TabRegistry
import io.github.szymonrychu.booxsketch.ui.TabSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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

    /** The board notebooks, one per conversation (T-46 OQ1 A). */
    val notebookFiles: NotebookFiles by lazy { NotebookFiles(dir) }

    /** The board notebook not yet bound to a conversation. */
    val notebookStore: NotebookStore by lazy { notebookFiles.store(null) }
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
            notebookFiles.deleteAll()
            specImages.deleteAll()
            File(app.cacheDir, ShareBundle.DIR).deleteRecursively()
            tokenStore.clear()
        }
        outboxScheduler.cancelAll()
        conversations.select(null)
        openDocument.value = null
        documentInk.forget()
        boardViewModel.reset()
        tasks.reset()
    }

    /** Starts on the selected conversation's notebook when it has one, else on the unbound one; see [runNotebookSync]. */
    val boardViewModel: BoardViewModel by lazy {
        val selected = conversations.selected.value?.takeIf { notebookFiles.forConversation(it).isFile }
        BoardViewModel(selected?.let(notebookFiles::store) ?: notebookStore, appScope, Dispatchers.IO, files = notebookFiles)
    }

    /** The plain touch input: the fallback, and the pen path of shape tools and the eraser on the Onyx path. */
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
            afterRefresh = {
                specSync.sync()
                feedbackSync.sync()
            },
            annotations = annotationStore,
            notebooks = notebookFiles,
            specImages = specImages,
        )
    }

    /** Region images of specs, per conversation, under `files/spec-images/`. */
    val specImages: SpecImageCache by lazy { SpecImageCache(File(dir, "spec-images"), ::connector) }

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
            paginate = { markdown ->
                withContext(Dispatchers.Default) {
                    val images = { id: String -> specImages.cached(row.serverId, id)?.let { BitmapFactory.decodeFile(it.path) } }
                    MarkdownPaginator(documentLayout, mermaid, images = images).paginate(markdown)
                }
            },
            store = annotationStore,
            layout = documentLayout,
            scope = appScope,
            prefetch = { markdown -> specImages.prefetch(row.serverId, markdown) },
        ).also { vm ->
            vm.revertTo = { version, latest ->
                _sendMessage.value = sendOutcomeText(sendUseCase.revert(vm.conversationLocalId, version, latest))
            }
        }

    /** The ink of documents, one notebook per conversation and version. */
    val documentInk: DocumentInk by lazy { DocumentInk(annotationStore, appScope) }

    /** The sketches spec [version] of [serverId] was made from. */
    fun sourcesViewModel(
        serverId: String,
        version: Int,
    ): SourcesViewModel = SourcesViewModel(serverId, version, database, ::connector, File(app.cacheDir, "sources"), appScope)

    /** Copies spec versions into Room: after a conversations refresh and while the Documents tab is shown. */
    val specSync: SpecSync by lazy { SpecSync(database, ::connector, { conversations.latestSpecVersions }) }

    /** Copies the translator's feedback into Room, on the same triggers as [specSync]. */
    val feedbackSync: FeedbackSync by lazy { FeedbackSync(database, ::connector, { conversations.latestFeedbackIds }) }

    /** A 401 pauses the queue (never fails items) and drops the token, so the gate shows Pairing. */
    val outboxRunner: OutboxRunner by lazy {
        OutboxRunner(
            database,
            ::connector,
            onNeedsPairing = pairingRepository::rejectToken,
            schedule = outboxScheduler::later,
            annotations = annotationStore,
            onFailed = { item ->
                withContext(Dispatchers.Main.immediate) { boardViewModel.dropFailedUpload(item.clientId, item.conversationLocalId) }
            },
            // A newer version exists: fetch it, so the board re-renders before the user sends again.
            onStaleBase = { conversations.refresh() },
            onAccepted = { item -> if (item.kind == OutboxKind.REVERT) conversations.refresh() },
        )
    }

    /** Places returned spec versions on the board (Task 4.6). */
    val notebookSync: NotebookSync by lazy {
        NotebookSync(
            database.specDao(),
            boardViewModel,
            boardViewModel.textHolds,
            feedback = database.feedbackDao(),
            threads = database.threadDao(),
        )
    }

    /** Shows the grey strike preview over struck words at pen idle (Task 5.3). */
    private val strikePreview: StrikePreviewSync by lazy { StrikePreviewSync(boardViewModel, boardViewModel.textHolds) }

    /**
     * While the app is paired and in the foreground: applies the spec versions of the board's conversation as they are
     * stored, and polls the conversations while the board waits for a version (pending segments or ops), since nothing else
     * fetches specs when the Documents tab is closed. The poll backs off from 5 s to 1 min and stops after about half
     * an hour ([pendingPollDelay]) and releases what is still pending as plain ink; a new upload starts it again. A
     * deleted or pruned conversation leaves the board.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runNotebookSync() =
        coroutineScope {
            launch { strikePreview.run() }
            // The board shows the selected conversation's notebook; the first one adopts an unbound board.
            launch { conversations.selected.collect { id -> id?.let { boardViewModel.bindConversation(it) } } }
            launch {
                combine(boardViewModel.notebook.map { it.conversationLocalId }, database.conversationDao().observeAll()) { bound, all ->
                    bound?.takeIf { id -> all.none { it.localId == id } }
                }.distinctUntilChanged()
                    .collect { gone -> gone?.let { boardViewModel.conversationGone(it) } }
            }
            launch {
                combine(boardViewModel.notebook.map { it.conversationLocalId }, conversations.selected) { bound, selected ->
                    bound
                        ?: selected
                }.distinctUntilChanged()
                    .flatMapLatest { localId ->
                        database.conversationDao().observeAll().map { all ->
                            all.firstOrNull { it.localId == localId }?.serverId?.let { it to localId }
                        }
                    }.distinctUntilChanged()
                    .flatMapLatest { ids ->
                        ids?.let { (serverId, _) -> database.specDao().observeVersions(serverId).map { ids } } ?: emptyFlow()
                    }.collect { (serverId, localId) -> notebookSync.catchUp(serverId, checkNotNull(localId)) }
            }
            launch {
                boardViewModel.notebook
                    .map { n -> n.pages.flatMap { p -> p.pendingSegments.map { it.clientId } + p.pendingOps.map { it.clientId } }.toSet() }
                    .distinctUntilChanged()
                    .collectLatest { waiting ->
                        // A translation that never answers must not leave the strokes pending forever.
                        if (waiting.isNotEmpty()) pollPending(conversations::refresh) { boardViewModel.releasePending(null) }
                    }
            }
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

                    override suspend fun addPending(
                        pending: Map<Int, List<PendingSegment>>,
                        ops: Map<Int, List<PendingOp>>,
                    ) = withContext(Dispatchers.Main.immediate) { boardViewModel.addPending(pending, ops) }
                },
            export = PngExporter(CanvasStrokeRenderer())::export,
            limits = configCache::current,
            outboxRoot = outboxRoot,
            kick = ::kickOutbox,
            annotations = annotationStore,
            // The export shows text and visible ink, without the pending ticks and the region frames.
            boardPage = { page -> boardViewModel.textLayers.layer.render(page.copy(pendingSegments = emptyList()), null) },
            measure = boardViewModel.textLayers.layer.measure,
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

    /**
     * Reverts the open document to its shown older version (Q15 B); a refusal shows in the send dialog.
     * The revert is triggered from the Documents version picker only (T-46 OQ7 A).
     */
    @Suppress("TooGenericExceptionCaught")
    fun onRevert(vm: DocumentViewModel) {
        appScope.launch {
            try {
                vm.revert()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "revert failed: $e")
                _sendMessage.value = "Not reverted: ${e.message ?: e.javaClass.simpleName}"
            }
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

    private fun taskConnector(): ConnectorClient? =
        tokenStore.originFlow.value?.let { ConnectorClient(it, tokenStore::token, okHttpClient) }

    /** Navigation and view models of the Tasks tab; the change stream is collected only while the tab is on screen. */
    val tasks: TasksController by lazy {
        TasksController(
            clientProvider = { taskConnector()?.let(::TasksClient) },
            events = flow { taskConnector()?.let { emitAll(TaskEvents(it, okHttpClient).changes()) } },
            scope = appScope,
            onUnauthorized = { appScope.launch { pairingRepository.rejectToken() } },
        )
    }

    /** Top-level tabs of the app shell; the Drawing tab hosts the board. Declared before init, which registers into it. */
    val tabs = TabRegistry()

    init {
        registerFeatures()
    }

    private fun registerFeatures() {
        // One line per feature, added by its task.
        tabs.register(TabSpec(BOARD_TAB_ID, "Drawing", order = DRAWING_TAB_ORDER) { })
        tabs.register(TabSpec(TASKS_TAB_ID, "Tasks", order = TASKS_TAB_ORDER) { TasksTab(it) })
        // wave C: the Documents tab registers here (it is a panel tab until then)
        PanelTabs.register(PanelTab("diagnostics", "Diagnostics", DIAGNOSTICS_ORDER) { DiagnosticsTab(inputSelector) })
        PanelTabs.register(PanelTab(CONVERSATIONS_TAB_ID, "Conversations", order = 0) { ConversationsTab() })
        PanelTabs.register(PanelTab(DOCUMENTS_TAB_ID, "Documents", DOCUMENTS_PANEL_TAB_ORDER) { DocumentsTab() })
        PanelTabs.onOpenConversations = { PanelTabs.select(CONVERSATIONS_TAB_ID) }
    }

    companion object {
        private const val TAG = "BooxSketch"
        private const val DOCUMENT_DPI = 300f
        private const val DIAGNOSTICS_ORDER = 900

        private const val PENDING_POLL_FIRST_MS = 5_000L
        private const val PENDING_POLL_MAX_MS = 60_000L
        private const val PENDING_POLL_ATTEMPTS = 33

        /** Refreshes on the [pendingPollDelay] schedule, then calls [giveUp] once: nothing answered, the wait is over. */
        internal suspend fun pollPending(
            refresh: suspend () -> Unit,
            giveUp: () -> Unit,
        ) {
            var attempt = 0
            while (true) {
                delay(pendingPollDelay(attempt++) ?: break)
                refresh()
            }
            giveUp()
        }

        /**
         * The wait before refresh number [attempt] (0-based) while the board waits for a spec version: 5 s doubling up
         * to 1 min, null after [PENDING_POLL_ATTEMPTS] refreshes (about half an hour), so a translation that never
         * answers does not poll forever.
         */
        internal fun pendingPollDelay(attempt: Int): Long? =
            if (attempt >= PENDING_POLL_ATTEMPTS) null else minOf(PENDING_POLL_FIRST_MS shl minOf(attempt, 4), PENDING_POLL_MAX_MS)

        const val DRAWING_TAB_ORDER = 10
        const val TASKS_TAB_ORDER = 20
        const val DOCUMENTS_TAB_ORDER = 30
    }
}
