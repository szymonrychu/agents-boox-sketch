package io.github.szymonrychu.booxsketch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.board.BoardViewModel
import io.github.szymonrychu.booxsketch.board.NotebookStore
import io.github.szymonrychu.booxsketch.board.NotebookSync
import io.github.szymonrychu.booxsketch.board.TextLayerHolds
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.documents.SpecSync
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.PendingOp
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.render.PngExporter
import io.github.szymonrychu.booxsketch.send.ConfigCache
import io.github.szymonrychu.booxsketch.send.OutboxRunner
import io.github.szymonrychu.booxsketch.send.SendBoard
import io.github.szymonrychu.booxsketch.send.SendOutcome
import io.github.szymonrychu.booxsketch.send.SendUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

/**
 * A notebook page through the real send path on a device (Task 4.8): handwriting on row 4 is segmented and uploaded
 * with its layout part (the connector double answers 202), the next conversations refresh finds spec version 1 with
 * a layout naming that segment, and the board shows the line as a text run on row 4 with the handwriting hidden.
 */
@RunWith(AndroidJUnit4::class)
class NotebookSendTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val g = NotebookGeometry(1404, 1872)
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val uploads = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dir = File(context.cacheDir, "notebook-send-test").apply { deleteRecursively() }
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val body =
                        when {
                            path.endsWith("/sketches") -> {
                                uploads += request.body.clone().readUtf8()
                                """{"sketch_id":"sk-1","conversation_id":"srv-1","status":"queued"}"""
                            }

                            path.endsWith("/conversations") -> {
                                """[{"id":"srv-1","title":"t","created_at":"2026-11-07T00:00:00Z","latest_spec_version":1}]"""
                            }

                            path.endsWith("/spec/1") -> {
                                """{"version":1,"markdown":"buy milk","created":"2026-11-07T00:00:00Z","sketch_ids":["sk-1"],""" +
                                    """"layout":{"lines":[{"line_id":"L1","source_line":1,"origin":{"segments":["p0-r4-0"]}}],"ops":[]}}"""
                            }

                            else -> {
                                return MockResponse().setResponseCode(404).setBody("""{"error":"not_found","detail":""}""")
                            }
                        }
                    return MockResponse()
                        .setResponseCode(
                            if (path.endsWith("/sketches")) 202 else 200,
                        ).setHeader("Content-Type", "application/json")
                        .setBody(body)
                }
            }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        dir.deleteRecursively()
    }

    private fun handwriting(id: String): Stroke {
        val y = g.rowTop(4) + 70f
        return Stroke(id, Tool.BALLPOINT, 3f, listOf(Point(100f, y, 0.5f, 0L), Point(400f, y, 0.5f, 20L)))
    }

    @Test
    fun send_notebook_page_through_outbox() =
        runBlocking<Unit> {
            val client = ConnectorClient("http://127.0.0.1:${server.port}", { "test-token" }, OkHttpClient())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val vm = withContext(Dispatchers.Main) { BoardViewModel(NotebookStore(File(dir, "n.json")), scope, Dispatchers.IO) }
            withContext(Dispatchers.Main) { vm.onStroke(handwriting("w1")) }
            val prefs = context.getSharedPreferences("notebook-send-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
            lateinit var repo: ConversationRepository
            val specSync = SpecSync(db, { client }, { repo.latestSpecVersions })
            repo = ConversationRepository(db, { client }, prefs, File(dir, "outbox"), {}, afterRefresh = { specSync.sync() })
            val board =
                object : SendBoard {
                    override fun notebook() = vm.notebook.value

                    override suspend fun markSent(sent: Map<Int, Page>) = withContext(Dispatchers.Main) { vm.markSentIfUnchanged(sent) }

                    override suspend fun addPending(
                        pending: Map<Int, List<PendingSegment>>,
                        ops: Map<Int, List<PendingOp>>,
                    ) = withContext(Dispatchers.Main) { vm.addPending(pending, ops) }
                }
            val send =
                SendUseCase(
                    db = db,
                    conversations = repo,
                    board = board,
                    export = PngExporter(CanvasStrokeRenderer())::export,
                    limits = { ConfigCache.DEFAULT },
                    outboxRoot = File(dir, "outbox"),
                    kick = {},
                )
            assertThat(send.send(all = false, caption = null)).isInstanceOf(SendOutcome.Queued::class.java)
            assertThat(
                vm.notebook.value.pages[0]
                    .pendingSegments,
            ).hasSize(1)

            assertThat(OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {}).drain().accepted).isEqualTo(1)
            assertThat(uploads.single()).contains("layout.0.json")

            assertThat(repo.refresh()).isNull()
            NotebookSync(db.specDao(), vm, TextLayerHolds()).catchUp("srv-1", repo.current().localId)

            val page = vm.notebook.value.pages[0]
            val run = page.text.single()
            assertThat(run.display).isEqualTo("buy milk")
            assertThat(run.row).isEqualTo(4)
            assertThat(page.consumed).containsExactly("w1")
            assertThat(page.visibleStrokes).isEmpty()
            assertThat(page.pendingSegments).isEmpty()
        }
}
