package io.github.szymonrychu.booxsketch.send

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.App
import io.github.szymonrychu.booxsketch.AppContainer
import io.github.szymonrychu.booxsketch.MainActivity
import io.github.szymonrychu.booxsketch.PairedApp
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.OutboxEntity
import io.github.szymonrychu.booxsketch.data.ThreadItemEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = PairedApp::class, sdk = [35])
class UploadWorkerTest {
    private lateinit var app: App
    private lateinit var container: AppContainer
    private lateinit var server: MockWebServer
    private lateinit var double: ConnectorDouble
    private lateinit var origin: String

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        container = app.container
        double = ConnectorDouble(delayMs = 30)
        server = MockWebServer().apply { dispatcher = double }
        server.start()
        origin = server.url("/").toString().trimEnd('/')
        runBlocking { container.tokenStore.save(origin, "test-token") }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun worker() = TestListenableWorkerBuilder<UploadWorker>(app).build()

    /** Queues [n] messages in conversation [local] (server id [serverId]) the way SendUseCase writes them. */
    private fun queueMessages(
        local: String,
        serverId: String,
        n: Int,
    ): List<String> =
        runBlocking {
            container.database.conversationDao().upsert(ConversationEntity(local, serverId, local, 0, 0))
            (1..n).map { i ->
                val clientId = "00000000-0000-4000-8000-$local${"%07d".format(i)}"
                val dir = File(container.outboxRoot, clientId).apply { mkdirs() }
                Payload.writeMessage(dir, clientId, "message $local $i")
                container.database.outboxDao().insert(
                    OutboxEntity(
                        clientId = clientId,
                        kind = OutboxKind.MESSAGE,
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
                clientId
            }
        }

    private fun states(vararg locals: String) =
        runBlocking { locals.flatMap { container.database.outboxDao().forConversation(it) }.map { it.state } }

    @Test
    fun unpair_cancels_the_queued_runs() =
        runBlocking<Unit> {
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
            val scheduler = OutboxScheduler(app)
            scheduler.later(60_000)
            scheduler.kick()
            container.unpair()
            val wm = WorkManager.getInstance(app)
            val states =
                (OutboxScheduler.LATER_WORKS + OutboxScheduler.KICK_WORK)
                    .flatMap { wm.getWorkInfosForUniqueWork(it).get() }
                    .map { it.state }
            assertThat(states).isNotEmpty()
            assertThat(states.toSet()).containsExactly(WorkInfo.State.CANCELLED)
        }

    @Test
    fun worker_drains_one_at_a_time() =
        runBlocking<Unit> {
            val a = queueMessages("aaaaa", "srv-a", 3)
            val b = queueMessages("bbbbb", "srv-b", 2)
            val results = listOf(worker(), worker()).map { w -> async(Dispatchers.Default) { w.doWork() } }.awaitAll()
            assertThat(results).containsExactly(ListenableWorker.Result.success(), ListenableWorker.Result.success())
            assertThat(double.maxInFlight.get()).isEqualTo(1)
            assertThat(states("aaaaa", "bbbbb")).containsExactly(*Array(5) { OutboxState.ACCEPTED })
            val sent =
                double.requests.map {
                    Json
                        .parseToJsonElement(
                            it.body.clone().readUtf8(),
                        ).jsonObject["client_id"]!!
                        .jsonPrimitive.content
                }
            // Each item exactly once, in queue order.
            assertThat(sent).containsExactlyElementsIn(a + b).inOrder()
        }

    @Test
    fun `401_pauses_and_flags_pairing`() =
        runBlocking<Unit> {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            queueMessages("ccccc", "srv-c", 2)
            double.uploadStatus = 401
            assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
            assertThat(states("ccccc")).containsExactly(OutboxState.PAUSED_AUTH, OutboxState.PAUSED_AUTH)
            assertThat(double.requests).hasSize(1)
            // The token is dropped, so the gate shows Pairing; the notification opens the app.
            assertThat(container.tokenStore.token()).isNull()
            val nm = app.getSystemService(NotificationManager::class.java)
            val posted = shadowOf(nm).allNotifications.single()
            val intent = shadowOf(posted.contentIntent).savedIntent
            assertThat(intent.component!!.className).isEqualTo(MainActivity::class.java.name)

            // Re-pairing resumes the queue.
            double.uploadStatus = 202
            container.tokenStore.save(origin, "test-token")
            container.outboxRunner.resumeAfterPairing()
            assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
            assertThat(states("ccccc")).containsExactly(OutboxState.ACCEPTED, OutboxState.ACCEPTED)
        }

    @Test
    fun `401_without_notification_permission_still_pauses`() =
        runBlocking<Unit> {
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            queueMessages("ddddd", "srv-d", 1)
            double.uploadStatus = 401
            assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
            assertThat(states("ddddd")).containsExactly(OutboxState.PAUSED_AUTH)
            assertThat(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications).isEmpty()
        }

    @Test
    fun `401_marks_thread_items_paused_and_repairing_queues_them_again`() =
        runBlocking<Unit> {
            val ids = queueMessages("eeeee", "srv-e", 2)
            ids.forEach {
                container.database.threadDao().upsert(
                    ThreadItemEntity(it, "eeeee", ThreadKind.FEEDBACK, it, "hi", 0, null, ThreadStatus.QUEUED, 0),
                )
            }
            double.uploadStatus = 401
            worker().doWork()

            fun threadStatuses() =
                runBlocking {
                    container.database
                        .threadDao()
                        .list("eeeee")
                        .map { it.status }
                }
            assertThat(threadStatuses()).containsExactly(ThreadStatus.PAUSED, ThreadStatus.PAUSED)
            container.outboxRunner.resumeAfterPairing()
            assertThat(threadStatuses()).containsExactly(ThreadStatus.QUEUED, ThreadStatus.QUEUED)
        }
}
