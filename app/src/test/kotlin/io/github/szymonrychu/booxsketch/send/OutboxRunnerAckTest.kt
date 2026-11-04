package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.render.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OutboxRunnerAckTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var double: ConnectorDouble
    private lateinit var useCase: SendUseCase
    private lateinit var client: ConnectorClient
    private lateinit var repo: ConversationRepository
    private var ids = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        double = ConnectorDouble()
        server = MockWebServer().apply { dispatcher = double }
        server.start()
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        val outboxRoot = tmp.newFolder("outbox")
        repo = ConversationRepository(db, { client }, context.getSharedPreferences("ack", Context.MODE_PRIVATE), outboxRoot, {})
        useCase =
            SendUseCase(
                db = db,
                conversations = repo,
                board = FakeBoard(Notebook(CANVAS, listOf(page(true, "a")), 0)),
                export = { _, _, _ -> ExportResult.Exported(ByteArray(PNG_SIZE) { 1 }, 300, 400) },
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

    private fun runner() = OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {})

    private fun thread(clientId: String) = runBlocking { db.threadDao().list(repo.current().localId).single { it.clientId == clientId } }

    @Test
    fun ack_sets_thread_item_server_id() =
        runBlocking<Unit> {
            val queued = useCase.send(all = false, caption = null) as SendOutcome.Queued
            assertThat(thread(queued.clientId).serverId).isNull()
            runner().drain()
            assertThat(thread(queued.clientId).serverId).isEqualTo("sk-1")
        }

    @Test
    fun replay_200_sets_same_server_id() =
        runBlocking<Unit> {
            val queued = useCase.send(all = false, caption = null) as SendOutcome.Queued
            double.uploadStatus = 200
            runner().drain()
            assertThat(thread(queued.clientId).serverId).isEqualTo("sk-1")
        }

    @Test
    fun failed_upload_leaves_server_id_null() =
        runBlocking<Unit> {
            val queued = useCase.send(all = false, caption = null) as SendOutcome.Queued
            double.uploadStatus = 503
            runner().drain()
            assertThat(thread(queued.clientId).serverId).isNull()
        }

    private companion object {
        const val PNG_SIZE = 100
    }
}
