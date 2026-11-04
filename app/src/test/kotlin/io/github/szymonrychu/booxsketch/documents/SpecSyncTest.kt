package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
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
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SpecSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    /** Status per `<server id>/<version>`; anything else answers 200 with a spec of that version. */
    private val statuses = mutableMapOf<String, Int>()
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
                    if (path.endsWith("/conversations")) {
                        return json(200, """[{"id":"srv-1","created_at":"2026-11-04T00:00:00Z","latest_spec_version":2}]""")
                    }
                    requests += path
                    val m =
                        Regex("/conversations/([^/]+)/spec/(\\d+)").find(path) ?: return json(404, """{"error":"not_found","detail":""}""")
                    val (id, v) = m.destructured
                    val status = statuses["$id/$v"] ?: 200
                    return if (status == 200) {
                        json(200, """{"version":$v,"markdown":"# v$v","created":"2026-11-04T00:00:0${v}Z","sketch_ids":["sk-$v","sk-x"]}""")
                    } else {
                        json(status, """{"error":"e","detail":""}""")
                    }
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

    private fun sync() = SpecSync(db, { client }, { latest })

    private fun conversation(
        local: String,
        serverId: String?,
        deleted: Boolean = false,
    ) = runBlocking { db.conversationDao().upsert(ConversationEntity(local, serverId, local, 0, 0, deleted)) }

    @Test
    fun fetches_only_missing_versions() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            db.specDao().upsert(SpecEntity("srv-1", 1, "# old", "x"))
            latest = mapOf("srv-1" to 3)
            assertThat(sync().sync()).isEqualTo(2)
            assertThat(requests.map { it.substringAfterLast('/') }).containsExactly("2", "3").inOrder()
            assertThat(db.specDao().versions("srv-1")).containsExactly(1, 2, 3)
            assertThat(
                db
                    .specDao()
                    .all("srv-1")
                    .first()
                    .markdown,
            ).isEqualTo("# old")
        }

    @Test
    fun stores_sketch_ids_as_json() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            latest = mapOf("srv-1" to 1)
            sync().sync()
            assertThat(db.specDao().latest("srv-1")!!.sketchIds).isEqualTo("""["sk-1","sk-x"]""")
        }

    @Test
    fun second_run_makes_no_requests() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            latest = mapOf("srv-1" to 2)
            val sync = sync()
            sync.sync()
            requests.clear()
            assertThat(sync.sync()).isEqualTo(0)
            assertThat(requests).isEmpty()
        }

    @Test
    fun a_404_on_a_version_skips_the_conversation_keeps_others() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            conversation("c2", "srv-2")
            statuses["srv-1/2"] = 404
            latest = mapOf("srv-1" to 3, "srv-2" to 2)
            sync().sync()
            assertThat(db.specDao().versions("srv-1")).containsExactly(1)
            assertThat(db.specDao().versions("srv-2")).containsExactly(1, 2)
        }

    @Test
    fun a_401_stops_without_partial_rows() =
        runBlocking<Unit> {
            conversation("c1", "srv-1")
            conversation("c2", "srv-2")
            statuses["srv-1/1"] = 401
            latest = mapOf("srv-1" to 2, "srv-2" to 2)
            assertThat(sync().sync()).isEqualTo(0)
            assertThat(db.specDao().versions("srv-1")).isEmpty()
            assertThat(db.specDao().versions("srv-2")).isEmpty()
        }

    @Test
    fun deleted_conversation_is_skipped() =
        runBlocking<Unit> {
            conversation("c1", "srv-1", deleted = true)
            latest = mapOf("srv-1" to 2)
            assertThat(sync().sync()).isEqualTo(0)
            assertThat(requests).isEmpty()
        }

    @Test
    fun refresh_triggers_sync() =
        runBlocking<Unit> {
            var calls = 0
            var seen: Map<String, Int> = emptyMap()
            lateinit var repo: ConversationRepository
            repo =
                ConversationRepository(
                    db,
                    { client },
                    ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("sync-test", Context.MODE_PRIVATE),
                    tmp.newFolder("outbox"),
                    {},
                    afterRefresh = {
                        calls++
                        seen = repo.latestSpecVersions
                    },
                )
            assertThat(repo.refresh()).isNull()
            assertThat(calls).isEqualTo(1)
            assertThat(seen).containsExactly("srv-1", 2)
        }
}
