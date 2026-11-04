package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.data.ThreadItemEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SourcesViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        server = MockWebServer().apply { start() }
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        scope = CoroutineScope(Job() + Dispatchers.Default)
        runBlocking { db.conversationDao().upsert(ConversationEntity("c1", "srv-1", "T", 0, 0)) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        db.close()
    }

    private suspend fun spec(ids: String) = db.specDao().upsert(SpecEntity("srv-1", 1, "# x", "x", ids))

    private suspend fun thread(
        sketchId: String,
        thumbDir: File?,
        text: String? = null,
    ) {
        db.threadDao().upsert(ThreadItemEntity("i-$sketchId", "c1", ThreadKind.SKETCH, "c-$sketchId", text, 2, thumbDir?.path, "sent", 1))
        db.threadDao().setServerId("c-$sketchId", sketchId)
    }

    private fun vm() = SourcesViewModel("srv-1", 1, db, { client }, File(tmp.root, "cache"), scope)

    private suspend fun SourcesViewModel.loaded() = withTimeout(10_000) { items.first { it != null }!! }

    @Test
    fun local_thumbs_listed_per_page() =
        runBlocking<Unit> {
            val dir =
                tmp.newFolder("t1").also {
                    listOf("sketch.1.png", "sketch.0.png", "x.json").forEach { n ->
                        File(it, n).writeText("p")
                    }
                }
            spec("""["s1"]""")
            thread("s1", dir)
            val item = vm().loaded().single()
            assertThat(item.state).isEqualTo(SourceState.LOCAL)
            assertThat(item.thumbs.map { it.name }).containsExactly("sketch.0.png", "sketch.1.png").inOrder()
            assertThat(item.kind).isEqualTo(SourceKind.DRAWING)
        }

    @Test
    fun notes_kind_from_caption_prefix() =
        runBlocking<Unit> {
            val dir = tmp.newFolder("t2").also { File(it, "sketch.0.png").writeText("p") }
            spec("""["s1"]""")
            thread("s1", dir, text = "${ANNOTATION_CAPTION_PREFIX}2")
            assertThat(vm().loaded().single().kind).isEqualTo(SourceKind.NOTES)
        }

    @Test
    fun missing_local_dir_fetches_page0() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            spec("""["s1"]""")
            thread("s1", File(tmp.root, "gone"))
            val item = vm().loaded().single()
            assertThat(item.state).isEqualTo(SourceState.REMOTE_PAGE0)
            assertThat(item.thumbs.single().readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        }

    @Test
    fun a_410_is_gone_without_crash() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"purged","detail":""}"""))
            spec("""["s1"]""")
            thread("s1", null)
            assertThat(vm().loaded().single().state).isEqualTo(SourceState.GONE)
        }

    @Test
    fun unknown_sketch_id_shows_gone() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found","detail":""}"""))
            spec("""["nobody"]""")
            assertThat(vm().loaded().single().state).isEqualTo(SourceState.GONE)
        }

    @Test
    fun no_sketch_ids_shows_empty_list() =
        runBlocking<Unit> {
            spec("[]")
            assertThat(vm().loaded()).isEmpty()
        }
}
