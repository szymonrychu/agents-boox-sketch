package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FeedbackSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    /** Feedback ids per server conversation id the fake connector holds; it pages by [pageSize]. */
    private val held = mutableMapOf<String, List<Int>>()
    private var pageSize = 100
    private val failFor = mutableMapOf<String, Int>()
    private var latest = mapOf<String, Int>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += path
                    val m =
                        Regex("/conversations/([^/]+)/feedback\\?after=(\\d+)").find(path)
                            ?: return json(404, """{"error":"not_found","detail":""}""")
                    val (id, after) = m.destructured
                    failFor[id]?.let { return json(it, """{"error":"e","detail":""}""") }
                    val items = held[id].orEmpty().filter { it > after.toInt() }.take(pageSize)
                    val body =
                        items.joinToString(",") {
                            """{"id":$it,"kind":"note","text":"t$it","where":"w$it","spec_version":1,"created_at":"2026-11-04T00:00:00Z"}"""
                        }
                    return json(200, """{"items":[$body],"cursor":${items.lastOrNull() ?: after}}""")
                }
            }
        server.start()
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sync() = FeedbackSync(db, { client }, { latest })

    private fun conversation(
        local: String,
        serverId: String?,
    ) = runBlocking { db.conversationDao().upsert(ConversationEntity(local, serverId, local, 0, 0, false)) }

    @Test
    fun fetches_items_after_the_stored_maximum() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            held["srv-1"] = listOf(1, 2, 3)
            latest = mapOf("srv-1" to 3)
            db.feedbackDao().upsert(FeedbackEntity("srv-1", 1, "note", "old", null, null, "x"))
            assertThat(sync().sync()).isEqualTo(2)
            assertThat(requests.single()).endsWith("/feedback?after=1")
            assertThat(db.feedbackDao().all("srv-1").map { it.id }).containsExactly(1, 2, 3).inOrder()
            assertThat(
                db
                    .feedbackDao()
                    .all("srv-1")
                    .first()
                    .text,
            ).isEqualTo("old")
            assertThat(
                db
                    .feedbackDao()
                    .all("srv-1")
                    .last()
                    .whereText,
            ).isEqualTo("w3")
        }

    @Test
    fun pages_until_the_latest_id_is_stored() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            held["srv-1"] = (1..5).toList()
            latest = mapOf("srv-1" to 5)
            pageSize = 2
            assertThat(sync().sync()).isEqualTo(5)
            assertThat(requests.map { it.substringAfter("after=") }).containsExactly("0", "2", "4").inOrder()
        }

    @Test
    fun makes_no_request_when_nothing_changed() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            conversation("c2", "srv-2")
            conversation("c3", null)
            held["srv-1"] = listOf(1, 2)
            latest = mapOf("srv-1" to 2)
            val sync = sync()
            sync.sync()
            requests.clear()
            assertThat(sync.sync()).isEqualTo(0)
            assertThat(requests).isEmpty()
        }

    @Test
    fun a_connector_without_feedback_ids_is_left_alone() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            latest = emptyMap()
            assertThat(sync().sync()).isEqualTo(0)
            assertThat(requests).isEmpty()
        }

    @Test
    fun a_404_skips_that_conversation_keeps_the_others_and_is_retried() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            conversation("c2", "srv-2")
            held["srv-1"] = listOf(1)
            held["srv-2"] = listOf(1)
            latest = mapOf("srv-1" to 1, "srv-2" to 1)
            failFor["srv-1"] = 404
            val s = sync()
            assertThat(s.sync()).isEqualTo(1)
            assertThat(db.feedbackDao().maxId("srv-2")).isEqualTo(1)
            assertThat(db.feedbackDao().maxId("srv-1")).isNull()
            failFor.clear()
            assertThat(s.sync()).isEqualTo(1)
        }

    @Test
    fun an_expired_token_stops_the_run() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            conversation("c2", "srv-2")
            held["srv-1"] = listOf(1)
            held["srv-2"] = listOf(1)
            latest = mapOf("srv-1" to 1, "srv-2" to 1)
            failFor["srv-1"] = 401
            failFor["srv-2"] = 401
            assertThat(sync().sync()).isEqualTo(0)
            assertThat(requests).hasSize(1)
        }

    @Test
    fun a_missing_client_does_nothing() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            latest = mapOf("srv-1" to 1)
            assertThat(FeedbackSync(db, { null }, { latest }).sync()).isEqualTo(0)
        }
}
