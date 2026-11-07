package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxReasons
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.render.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** Revert to a spec version (Q15 B) through the outbox (Task 4.7). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendUseCaseRevertTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private lateinit var useCase: SendUseCase
    private val requests = mutableListOf<RecordedRequest>()
    private var answer: MockResponse = MockResponse().setResponseCode(201).setBody("""{"version":5,"reverted_from":2}""")
    private var ids = 0

    /** Answers by request path suffix; any other path gets [answer]. */
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            requests += request
                            return (
                                routes.entries
                                    .firstOrNull {
                                        request.path.orEmpty().endsWith(it.key)
                                    }?.value ?: answer
                            ).clone().setHeader("Content-Type", "application/json")
                        }
                    }
            }
        server.start()
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        val outboxRoot = tmp.newFolder("outbox")
        val repo =
            ConversationRepository(
                db,
                { client },
                context.getSharedPreferences("revert", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                outboxRoot,
                {},
            )
        runBlocking { db.conversationDao().upsert(ConversationEntity("local-1", "srv-1", "t", 0, 1)) }
        useCase =
            SendUseCase(
                db = db,
                conversations = repo,
                board = FakeBoard(Notebook(CANVAS, listOf(page(false)), 0)),
                export = { _, _, _ -> ExportResult.Empty },
                limits = { ConfigCache.DEFAULT },
                outboxRoot = outboxRoot,
                kick = {},
                worker = Dispatchers.Unconfined,
                newId = { "00000000-0000-4000-8000-00000000000${++ids}" },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun runner(synced: MutableList<String> = mutableListOf()) =
        OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {}, onStaleBase = { synced += "sync" }, onAccepted = {
            synced +=
                "accepted:${it.kind}"
        })

    @Test
    fun revert_enqueues_with_latest_as_base() =
        runBlocking<Unit> {
            val outcome = useCase.revert("local-1", version = 2, latest = 4)
            val clientId = (outcome as SendOutcome.Queued).clientId
            val item = db.outboxDao().forConversation("local-1").single()
            assertThat(item.kind).isEqualTo(OutboxKind.REVERT)
            val body = Json.parseToJsonElement(File(item.payloadDir, Payload.REVERT).readText()).jsonObject
            assertThat(body["version"]!!.jsonPrimitive.int).isEqualTo(2)
            assertThat(body["base_version"]!!.jsonPrimitive.int).isEqualTo(4)
            val thread = db.threadDao().list("local-1").single()
            assertThat(thread.kind).isEqualTo(ThreadKind.REVERT)
            assertThat(thread.text).isEqualTo("Revert to v2")
            val events = mutableListOf<String>()
            runner(events).drain()
            val post = requests.single()
            assertThat(post.method).isEqualTo("POST")
            assertThat(post.path).endsWith("/conversations/srv-1/spec/2/revert")
            assertThat(post.body.readUtf8()).isEqualTo("""{"base_version":4}""")
            val done = db.outboxDao().forConversation("local-1").single()
            assertThat(done.state).isEqualTo(OutboxState.ACCEPTED)
            assertThat(done.clientId).isEqualTo(clientId)
            // The new version is fetched right away, so the board re-renders.
            assertThat(events).containsExactly("accepted:REVERT")
        }

    @Test
    fun revert_of_the_latest_version_is_refused() =
        runBlocking<Unit> {
            assertThat(useCase.revert("local-1", version = 4, latest = 4)).isEqualTo(SendOutcome.NothingToSend)
            assertThat(db.outboxDao().forConversation("local-1")).isEmpty()
        }

    @Test
    fun revert_busy_and_stale_reasons() =
        runBlocking<Unit> {
            answer =
                MockResponse()
                    .setResponseCode(
                        409,
                    ).setBody("""{"error":"busy","detail":"a translator turn of this conversation is open"}""")
            val busy = (useCase.revert("local-1", version = 1, latest = 3) as SendOutcome.Queued).clientId
            val events = mutableListOf<String>()
            runner(events).drain()
            assertThat(
                db
                    .threadDao()
                    .list("local-1")
                    .single { it.clientId == busy }
                    .status,
            ).isEqualTo(ThreadStatus.failed(OutboxReasons.BUSY))

            answer = MockResponse().setResponseCode(409).setBody("""{"error":"stale_base","detail":"spec version 4 is the latest"}""")
            val stale = (useCase.revert("local-1", version = 1, latest = 3) as SendOutcome.Queued).clientId
            runner(events).drain()
            assertThat(
                db
                    .threadDao()
                    .list("local-1")
                    .single { it.clientId == stale }
                    .status,
            ).isEqualTo(ThreadStatus.failed(OutboxReasons.STALE_BASE))
            assertThat(events).containsExactly("sync")
        }

    private fun spec(
        version: Int,
        markdown: String,
        layout: String?,
    ) = MockResponse().setResponseCode(200).setBody(
        """{"version":$version,"markdown":"$markdown","created":"2026-11-07T00:00:00Z","sketch_ids":["s1"],"layout":$layout}""",
    )

    @Test
    fun revert_replay_after_a_lost_answer_counts_as_accepted() =
        runBlocking<Unit> {
            // The first POST made v4 but its 201 was lost; the retry is a 409 stale_base, and v4 is the revert of v1.
            answer = MockResponse().setResponseCode(409).setBody("""{"error":"stale_base","detail":"spec version 4 is the latest"}""")
            routes["/conversations/srv-1/spec/4"] =
                spec(4, "one", """{"lines":[],"ops":[],"base_version":3,"reverted_from":1}""")
            val clientId = (useCase.revert("local-1", version = 1, latest = 3) as SendOutcome.Queued).clientId
            val events = mutableListOf<String>()
            runner(events).drain()
            val item = db.outboxDao().forConversation("local-1").single()
            assertThat(item.state).isEqualTo(OutboxState.ACCEPTED)
            assertThat(
                db
                    .threadDao()
                    .list("local-1")
                    .single { it.clientId == clientId }
                    .status,
            ).isEqualTo(ThreadStatus.SENT)
            assertThat(events).containsExactly("accepted:REVERT")
        }

    @Test
    fun revert_replay_of_a_version_without_layout_matches_its_markdown() =
        runBlocking<Unit> {
            answer = MockResponse().setResponseCode(409).setBody("""{"error":"stale_base","detail":"spec version 4 is the latest"}""")
            routes["/conversations/srv-1/spec/4"] = spec(4, "one", "null")
            routes["/conversations/srv-1/spec/1"] = spec(1, "one", "null")
            useCase.revert("local-1", version = 1, latest = 3)
            runner().drain()
            assertThat(
                db
                    .outboxDao()
                    .forConversation("local-1")
                    .single()
                    .state,
            ).isEqualTo(OutboxState.ACCEPTED)
        }

    @Test
    fun stale_base_with_an_unrelated_newer_version_still_fails() =
        runBlocking<Unit> {
            answer = MockResponse().setResponseCode(409).setBody("""{"error":"stale_base","detail":"spec version 4 is the latest"}""")
            routes["/conversations/srv-1/spec/4"] = spec(4, "typed feedback", """{"lines":[],"ops":[],"base_version":3}""")
            routes["/conversations/srv-1/spec/1"] = spec(1, "one", "null")
            useCase.revert("local-1", version = 1, latest = 3)
            val events = mutableListOf<String>()
            runner(events).drain()
            val item = db.outboxDao().forConversation("local-1").single()
            assertThat(item.state).isEqualTo(OutboxState.FAILED_PERMANENT)
            assertThat(item.lastError).isEqualTo(OutboxReasons.STALE_BASE)
            assertThat(events).containsExactly("sync")
        }
}
