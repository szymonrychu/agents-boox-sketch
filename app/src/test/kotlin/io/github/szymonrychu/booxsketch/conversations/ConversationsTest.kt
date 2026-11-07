package io.github.szymonrychu.booxsketch.conversations

import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.CursorEntity
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.data.OutboxEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.data.ThreadItemEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import io.github.szymonrychu.booxsketch.documents.SpecImageCache
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConversationsTest {
    @get:Rule val tmp = TemporaryFolder()

    @get:Rule val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: ConversationRepository
    private lateinit var outboxRoot: File
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var deleteStatus = 204
    private var listBody = "[]"
    private var listStatus = 200
    private var unauthorized = 0
    private var ids = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    val r = MockResponse().setHeader("Content-Type", "application/json")
                    return when (request.method) {
                        "DELETE" -> {
                            if (deleteStatus ==
                                204
                            ) {
                                r.setResponseCode(204)
                            } else {
                                r.setResponseCode(deleteStatus).setBody("""{"error":"x","detail":""}""")
                            }
                        }

                        else -> {
                            r.setResponseCode(listStatus).setBody(listBody)
                        }
                    }
                }
            }
        server.start()
        val client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        outboxRoot = File(tmp.root, "outbox")
        repo =
            ConversationRepository(
                db,
                { client },
                context.getSharedPreferences("conversations-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                outboxRoot,
                onUnauthorized = { unauthorized++ },
                clock = { 5_000L },
                newId = { "local-${++ids}" },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    /** A conversation with a thread item, a spec, a cursor and a queued outbox payload. */
    private fun seed(
        local: String,
        serverId: String?,
    ): File =
        runBlocking {
            db.conversationDao().upsert(ConversationEntity(local, serverId, "t-$local", 0, 0))
            db.threadDao().upsert(ThreadItemEntity("th-$local", local, ThreadKind.SKETCH, "c-$local", null, 1, null, "queued", 0))
            serverId?.let {
                db.specDao().upsert(SpecEntity(it, 1, "# spec", "2026-11-03T00:00:00Z"))
                db.feedbackDao().upsert(FeedbackEntity(it, 1, "note", "n", null, null, "2026-11-03T00:00:00Z"))
                db.cursorDao().set(CursorEntity(it, 7))
            }
            val dir = File(outboxRoot, "c-$local").apply { mkdirs() }
            File(dir, "pages.json").writeText("{}")
            db.outboxDao().insert(
                OutboxEntity(
                    clientId = "c-$local",
                    kind = OutboxKind.SKETCH,
                    conversationLocalId = local,
                    conversationServerId = null,
                    conversationMode = null,
                    payloadDir = dir.path,
                    state = OutboxState.PENDING,
                    attempts = 0,
                    nextAttemptAtMs = 0,
                    lastError = null,
                    serverId = null,
                ),
            )
            dir
        }

    @Test
    fun delete_removes_local_history() =
        runBlocking<Unit> {
            val dir = seed("l1", "srv-1")
            val keptDir = seed("l2", "srv-2")
            repo.select("l1")
            assertThat(repo.delete("l1")).isNull()
            assertThat(requests).containsExactly("DELETE /app/v1/sketch/conversations/srv-1")
            assertThat(db.conversationDao().get("l1")).isNull()
            assertThat(db.threadDao().list("l1")).isEmpty()
            assertThat(db.specDao().latest("srv-1")).isNull()
            assertThat(db.feedbackDao().all("srv-1")).isEmpty()
            assertThat(db.cursorDao().get("srv-1")).isNull()
            assertThat(db.outboxDao().forConversation("l1")).isEmpty()
            assertThat(dir.exists()).isFalse()
            assertThat(repo.selected.value).isNull()
            // The other conversation is untouched.
            assertThat(db.threadDao().list("l2")).hasSize(1)
            assertThat(db.specDao().latest("srv-2")).isNotNull()
            assertThat(db.feedbackDao().all("srv-2")).hasSize(1)
            assertThat(db.outboxDao().forConversation("l2")).hasSize(1)
            assertThat(keptDir.exists()).isTrue()
        }

    @Test
    fun delete_removes_the_spec_images_of_the_conversation() =
        runBlocking<Unit> {
            seed("l1", "srv-1")
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = File(tmp.root, "spec-images")
            File(root, "srv-1").mkdirs()
            File(root, "srv-1/r1.png").writeBytes(byteArrayOf(1))
            File(root, "srv-2").mkdirs()
            File(root, "srv-2/r2.png").writeBytes(byteArrayOf(2))
            val withImages =
                ConversationRepository(
                    db,
                    { ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient()) },
                    context.getSharedPreferences("conversations-images", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                    outboxRoot,
                    onUnauthorized = {},
                    specImages = SpecImageCache(root) { null },
                )
            assertThat(withImages.delete("l1")).isNull()
            assertThat(File(root, "srv-1").exists()).isFalse()
            assertThat(File(root, "srv-2/r2.png").exists()).isTrue()
        }

    @Test
    fun delete_offline_keeps_everything() =
        runBlocking<Unit> {
            val dir = seed("l1", "srv-1")
            deleteStatus = 503
            assertThat(repo.delete("l1")).contains("needs a connection")
            assertThat(db.conversationDao().get("l1")).isNotNull()
            assertThat(db.threadDao().list("l1")).hasSize(1)
            assertThat(dir.exists()).isTrue()
        }

    @Test
    fun delete_already_gone_on_server_still_deletes_locally() =
        runBlocking<Unit> {
            seed("l1", "srv-1")
            deleteStatus = 404
            assertThat(repo.delete("l1")).isNull()
            assertThat(db.conversationDao().get("l1")).isNull()
        }

    @Test
    fun delete_never_sent_conversation_is_local_only() =
        runBlocking<Unit> {
            seed("l1", null)
            assertThat(repo.delete("l1")).isNull()
            assertThat(requests).isEmpty()
            assertThat(db.conversationDao().get("l1")).isNull()
        }

    @Test
    fun refresh_merges_server_list_with_local_rows() =
        runBlocking<Unit> {
            db.conversationDao().upsert(ConversationEntity("mine", "srv-1", DEFAULT_CONVERSATION_TITLE, 0, 9))
            db.conversationDao().upsert(ConversationEntity("unsent", null, DEFAULT_CONVERSATION_TITLE, 0, 8))
            listBody =
                """[{"id":"srv-1","title":"Login form","created_at":"2026-11-03T10:00:00+00:00","latest_spec_version":2},""" +
                """{"id":"srv-2","title":null,"created_at":"2026-11-02T10:00:00+00:00","latest_spec_version":null}]"""
            assertThat(repo.refresh()).isNull()
            val all = db.conversationDao().all().associateBy { it.serverId }
            assertThat(all).hasSize(3)
            assertThat(all["srv-1"]!!.localId).isEqualTo("mine")
            assertThat(all["srv-1"]!!.title).isEqualTo("Login form")
            assertThat(all["srv-2"]!!.title).isEqualTo(DEFAULT_CONVERSATION_TITLE)
            assertThat(all["srv-2"]!!.createdAtMs).isEqualTo(1_793_613_600_000L)
            // A second refresh adds nothing.
            repo.refresh()
            assertThat(db.conversationDao().all()).hasSize(3)
        }

    @Test
    fun refresh_prunes_rows_the_complete_server_list_no_longer_has() =
        runBlocking<Unit> {
            db.conversationDao().upsert(ConversationEntity("keep", "srv-1", "t", 0, 1))
            db.conversationDao().upsert(ConversationEntity("gone", "srv-gone", "t", 0, 2))
            db.conversationDao().upsert(ConversationEntity("unsent", null, "t", 0, 3))
            db.specDao().upsert(SpecEntity("srv-gone", 1, "# spec", "2026-11-03T00:00:00Z"))
            db.cursorDao().set(CursorEntity("srv-gone", 7))
            seed("pending", "srv-pending")
            repo.select("gone")
            listBody = """[{"id":"srv-1","title":"t","created_at":"2026-11-03T10:00:00+00:00","latest_spec_version":null}]"""
            assertThat(repo.refresh()).isNull()
            assertThat(db.conversationDao().all().map { it.localId }).containsExactly("keep", "unsent", "pending")
            assertThat(db.specDao().latest("srv-gone")).isNull()
            assertThat(db.cursorDao().get("srv-gone")).isNull()
            assertThat(repo.selected.value).isNull()
        }

    @Test
    fun refresh_records_latest_feedback_ids() =
        runBlocking<Unit> {
            listBody =
                """[{"id":"srv-1","title":"t","created_at":"2026-11-03T10:00:00+00:00","latest_spec_version":1,"latest_feedback_id":4},""" +
                """{"id":"srv-2","title":"t","created_at":"2026-11-03T10:00:00+00:00","latest_spec_version":1}]"""
            assertThat(repo.refresh()).isNull()
            assertThat(repo.latestFeedbackIds).containsExactly("srv-1", 4)
        }

    @Test
    fun refresh_failure_prunes_nothing() =
        runBlocking<Unit> {
            db.conversationDao().upsert(ConversationEntity("gone", "srv-gone", "t", 0, 2))
            listStatus = 500
            listBody = """{"error":"boom","detail":""}"""
            assertThat(repo.refresh()).isNotNull()
            assertThat(db.conversationDao().all().map { it.localId }).containsExactly("gone")
        }

    @Test
    fun refresh_unauthorized_flags_pairing() =
        runBlocking<Unit> {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized","detail":""}""")
                }
            assertThat(repo.refresh()).isNotNull()
            assertThat(unauthorized).isEqualTo(1)
        }

    @Test
    fun new_conversation_is_selected_and_receives_sends() =
        runBlocking<Unit> {
            db.conversationDao().upsert(ConversationEntity("recent", "srv-9", "t", 0, 9_999))
            assertThat(repo.current().localId).isEqualTo("recent")
            val id = repo.newConversation()
            assertThat(repo.selected.value).isEqualTo(id)
            assertThat(repo.current().localId).isEqualTo(id)
            assertThat(repo.current().serverId).isNull()
            repo.select("recent")
            assertThat(repo.current().localId).isEqualTo("recent")
        }

    @Test
    fun list_selects_and_deletes_only_after_confirmation() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val rows =
            listOf(
                ConversationEntity("a", "srv-a", "First", 0, 2),
                ConversationEntity("b", null, "Second", 0, 1),
            )
        compose.setContent {
            EinkTheme { ConversationsScreen(rows, "a", null, {}, { selected += it }, { deleted += it }, {}) }
        }
        compose.onNodeWithTag(conversationRowTag("b")).performClick()
        assertThat(selected).containsExactly("b")
        compose.onNodeWithTag(conversationDeleteTag("a")).performClick()
        assertThat(deleted).isEmpty()
        compose.onNodeWithTag(TAG_CONFIRM_DELETE).performClick()
        assertThat(deleted).containsExactly("a")
    }

    @Test
    fun buttons_are_at_least_48dp_high() {
        val rows = listOf(ConversationEntity("a", "srv-a", "First", 0, 2))
        compose.setContent { EinkTheme { ConversationsScreen(rows, "a", null, {}, {}, {}, {}) } }
        compose.onNodeWithTag(TAG_NEW_CONVERSATION).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Refresh").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(conversationDeleteTag("a")).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(conversationDeleteTag("a")).performClick()
        compose.onNodeWithTag(TAG_CONFIRM_DELETE).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Cancel").assertHeightIsAtLeast(48.dp)
    }
}
