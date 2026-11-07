package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the document view shows. [readOnly] is true for every version but the latest and for a draft already sent, so
 * a newer version arriving leaves the open draft viewable but not sendable (nothing is lost).
 */
data class DocumentViewState(
    val version: Int = 0,
    val latestVersion: Int = 0,
    val pages: List<FrozenPage> = emptyList(),
    val pageIndex: Int = 0,
    val readOnly: Boolean = true,
    val loaded: Boolean = false,
) {
    /** True on an older version: it can be made the newest again (Q15 B, T-46 OQ7 A). */
    val canRevert: Boolean get() = loaded && version in 1 until latestVersion
}

/**
 * Reads one conversation's spec as frozen pages. Opening a version freezes its pages through [store] (or reuses an
 * earlier freeze), so reading and inking share one bitmap set; [prefetch] and [paginate] run only when there is no
 * freeze yet.
 */
class DocumentViewModel(
    val conversationLocalId: String,
    private val serverId: String,
    private val db: AppDatabase,
    private val paginate: suspend (markdown: String) -> List<RenderedPage>,
    private val store: AnnotationStore,
    private val layout: DocumentLayout,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Fetches what a version's markdown needs before it is paginated (its region images, see [SpecImageCache]). */
    private val prefetch: suspend (markdown: String) -> Unit = {},
) {
    private val _state = MutableStateFlow(DocumentViewState())
    val state: StateFlow<DocumentViewState> = _state.asStateFlow()

    private val _versions = MutableStateFlow<List<Int>>(emptyList())

    /** The stored versions, ascending: the version picker's entries. */
    val versions: StateFlow<List<Int>> = _versions.asStateFlow()

    private val _feedback = MutableStateFlow<List<FeedbackEntity>>(emptyList())

    /** The translator's feedback of this conversation, newest first. */
    val feedback: StateFlow<List<FeedbackEntity>> = _feedback.asStateFlow()

    init {
        scope.launch { db.feedbackDao().observe(serverId).collect { _feedback.value = it } }
        scope.launch {
            db.specDao().observeVersions(serverId).collect { list ->
                _versions.value = list
                val latest = list.lastOrNull() ?: return@collect
                if (_state.value.version ==
                    0
                ) {
                    show(latest, latest)
                } else {
                    _state.value = withReadOnly(_state.value.copy(latestVersion = latest))
                }
            }
        }
    }

    private fun withReadOnly(s: DocumentViewState) =
        s.copy(
            readOnly =
                s.version != s.latestVersion || store.isSent(conversationLocalId, s.version),
        )

    private suspend fun show(
        version: Int,
        latest: Int,
    ) {
        _state.value = DocumentViewState(version = version, latestVersion = latest, loaded = false)
        val frozen =
            withContext(io) {
                store.discardForRetry(conversationLocalId, version)
                store.frozen(conversationLocalId, version) ?: run {
                    val markdown =
                        db
                            .specDao()
                            .get(serverId, version)
                            ?.markdown
                            .orEmpty()
                    prefetch(markdown)
                    store.freeze(conversationLocalId, version, paginate(markdown), layout)
                }
            }
        // The user may have picked another version while this one froze.
        if (_state.value.version == version) {
            _state.value = withReadOnly(_state.value.copy(pages = frozen.pages, pageIndex = 0, loaded = true))
        }
    }

    fun selectVersion(version: Int) {
        if (version !in _versions.value || version == _state.value.version) return
        scope.launch { show(version, _state.value.latestVersion) }
    }

    fun next() = move(1)

    fun prev() = move(-1)

    private fun move(delta: Int) {
        val s = _state.value
        _state.value = s.copy(pageIndex = (s.pageIndex + delta).coerceIn(0, (s.pages.size - 1).coerceAtLeast(0)))
    }

    /** Called after the draft was sent: the view turns read-only. */
    fun refreshReadOnly() {
        _state.value = withReadOnly(_state.value)
    }

    /** Queues a revert to (version, latest); set by the host. */
    var revertTo: (suspend (version: Int, latest: Int) -> Unit)? = null

    /** Reverts to the shown version when it is an older one; the board re-renders once the new version arrives. */
    suspend fun revert() {
        val s = _state.value
        if (!s.canRevert) return
        revertTo?.invoke(s.version, s.latestVersion)
    }

    /** The markdown of the shown version, for Share. */
    suspend fun markdown(): String? = db.specDao().get(serverId, _state.value.version)?.markdown
}
