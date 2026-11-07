package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.App
import io.github.szymonrychu.booxsketch.PairedApp
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.render.PageRasterExporter
import io.github.szymonrychu.booxsketch.send.CANVAS
import io.github.szymonrychu.booxsketch.send.ConfigCache
import io.github.szymonrychu.booxsketch.send.FakeBoard
import io.github.szymonrychu.booxsketch.send.OutboxRunner
import io.github.szymonrychu.booxsketch.send.SendOutcome
import io.github.szymonrychu.booxsketch.send.SendUseCase
import io.github.szymonrychu.booxsketch.send.metaOf
import io.github.szymonrychu.booxsketch.send.page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Collections

/** The documents feature end to end against a connector double: sync, open, ink, send, a newer version, sources. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocumentsFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private lateinit var store: AnnotationStore
    private lateinit var scope: CoroutineScope
    private lateinit var repo: ConversationRepository
    private lateinit var sync: SpecSync
    private val uploads = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private var latest = 1
    private var offline = false
    private var specMarkdown = "# Title\n\nSome spec text.\n"
    private val layout = DocumentLayout(300, 400)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (offline) return json(503, """{"error":"down","detail":""}""")
                    return when {
                        request.method == "DELETE" -> {
                            MockResponse().setResponseCode(204)
                        }

                        path.contains("/images/") -> {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "image/png")
                                .setBody(okio.Buffer().write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)))
                        }

                        path.endsWith("/sketches") -> {
                            uploads += request
                            json(202, """{"sketch_id":"sk-1","conversation_id":"srv-1","status":"queued"}""")
                        }

                        path.endsWith("/conversations") -> {
                            json(
                                200,
                                """[{"id":"srv-1","title":"Doc","created_at":"2026-11-04T10:00:00Z","latest_spec_version":$latest}]""",
                            )
                        }

                        Regex("/conversations/srv-1/spec/(\\d+)").containsMatchIn(path) -> {
                            val v = Regex("/spec/(\\d+)").find(path)!!.groupValues[1]
                            val ids = if (v.toInt() >= 2) """["sk-1"]""" else "[]"
                            json(
                                200,
                                """{"version":$v,"markdown":${kotlinx.serialization.json.JsonPrimitive(
                                    specMarkdown,
                                )},"created":"2026-11-04T10:0$v:00Z","sketch_ids":$ids}""",
                            )
                        }

                        else -> {
                            json(404, """{"error":"not_found","detail":""}""")
                        }
                    }
                }
            }
        server.start()
        client = ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient())
        store = AnnotationStore(tmp.newFolder("files"))
        scope = CoroutineScope(Job() + Dispatchers.Default)
        sync = SpecSync(db, { client }, { repo.latestSpecVersions })
        repo =
            ConversationRepository(
                db,
                { client },
                context.getSharedPreferences("documents-flow", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
                tmp.newFolder("outbox"),
                {},
                afterRefresh = { sync.sync() },
                annotations = store,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        db.close()
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun viewModel(row: DocumentRow) =
        DocumentViewModel(row.conversationLocalId, row.serverId, db, { MarkdownPaginator(layout).paginate(it) }, store, layout, scope)

    private suspend fun rows(ok: (List<DocumentRow>) -> Boolean) =
        withTimeout(10_000) {
            DocumentsViewModel(db, scope)
                .page
                .first {
                    it.loaded &&
                        ok(it.rows)
                }.rows
        }

    private fun sendUseCase(outboxRoot: File) =
        SendUseCase(
            db = db,
            conversations = repo,
            board = FakeBoard(Notebook(CANVAS, listOf(page(true, "a")), 0)),
            export = { _, _, _ -> error("not used") },
            limits = { ConfigCache.DEFAULT },
            outboxRoot = outboxRoot,
            kick = {},
            worker = Dispatchers.Unconfined,
            newId = { "00000000-0000-4000-8000-000000000001" },
            annotations = store,
            exportPage = PageRasterExporter(CanvasStrokeRenderer())::export,
        )

    @Test
    fun flow_sync_open_ink_send_then_new_version_arrives() =
        runBlocking<Unit> {
            assertThat(repo.refresh()).isNull()
            val row = rows { it.size == 1 }.single()
            assertThat(row.latestVersion).isEqualTo(1)

            val vm = viewModel(row)
            val state = withTimeout(10_000) { vm.state.first { it.loaded } }
            assertThat(state.readOnly).isFalse()
            val ink = DocumentInk(store, scope)
            val board = ink.board(row.conversationLocalId, 1, state.pages.size, Canvas(layout.widthPx, layout.pageHeightPx, 300f))
            board.onStroke(page(true, "n1").strokes.single())
            val notebook = board.notebook.value
            val inputs = state.pages.mapIndexed { i, p -> AnnotatedInput(i, p.file, notebook.pages[i], i, p.sourceLines) }
            val outboxRoot = File(tmp.root, "outbox")
            val outcome = sendUseCase(outboxRoot).sendAnnotation(row.conversationLocalId, 1, 1, inputs, "check the title")
            assertThat(outcome).isInstanceOf(SendOutcome.Queued::class.java)
            assertThat(OutboxRunner(db, { client }, onNeedsPairing = {}, schedule = {}).drain().accepted).isEqualTo(1)

            val annotates = metaOf(uploads.single())["annotates"]!!.jsonObject
            assertThat(annotates["spec_version"]!!.jsonPrimitive.int).isEqualTo(1)
            vm.refreshReadOnly()
            assertThat(vm.state.value.readOnly).isTrue()

            latest = 2
            assertThat(repo.refresh()).isNull()
            val updated = rows { it.singleOrNull()?.latestVersion == 2 }.single()
            assertThat(updated.latestVersion).isEqualTo(2)
            val sources = SourcesViewModel("srv-1", 2, db, { client }, File(tmp.root, "cache"), scope)
            val items = withTimeout(10_000) { sources.items.first { it != null }!! }
            assertThat(items.map { it.sketchId }).containsExactly("sk-1")
            assertThat(items.single().kind).isEqualTo(SourceKind.NOTES)
            assertThat(items.single().state).isEqualTo(SourceState.LOCAL)
        }

    @Test
    fun empty_spec_opens_with_one_page() =
        runBlocking<Unit> {
            specMarkdown = ""
            repo.refresh()
            val row = rows { it.size == 1 }.single()
            val state = withTimeout(10_000) { viewModel(row).state.first { it.loaded } }
            assertThat(state.pages).hasSize(1)
        }

    @Test
    fun delete_conversation_removes_document_and_annotations() =
        runBlocking<Unit> {
            repo.refresh()
            val row = rows { it.size == 1 }.single()
            withTimeout(10_000) { viewModel(row).state.first { it.loaded } }
            assertThat(File(tmp.root, "files/annotations/${row.conversationLocalId}").exists()).isTrue()
            assertThat(repo.delete(row.conversationLocalId)).isNull()
            assertThat(rows { it.isEmpty() }).isEmpty()
            assertThat(File(tmp.root, "files/annotations/${row.conversationLocalId}").exists()).isFalse()
        }

    @Test
    fun offline_sync_keeps_existing_rows() =
        runBlocking<Unit> {
            repo.refresh()
            rows { it.size == 1 }
            offline = true
            assertThat(repo.refresh()).contains("Could not load")
            assertThat(db.specDao().versions("srv-1")).containsExactly(1)
            assertThat(rows { it.size == 1 }).hasSize(1)
        }

    @Test
    fun a_failed_version_fetch_leaves_no_partial_rows() =
        runBlocking<Unit> {
            db.conversationDao().upsert(ConversationEntity("c9", "srv-9", "T", 0, 0))
            val sync = SpecSync(db, { client }, { mapOf("srv-9" to 2) })
            assertThat(sync.sync()).isEqualTo(0)
            assertThat(db.specDao().versions("srv-9")).isEmpty()
        }

    @Test
    fun share_with_images_shares_zip() =
        runBlocking<Unit> {
            val region = "0b7f6a1e-2c3d-4e5f-8a9b-0c1d2e3f4a5b"
            specMarkdown = "# Title\n\n![image](images/$region.png)\n"
            repo.refresh()
            val row = rows { it.isNotEmpty() }.single()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val images = SpecImageCache(tmp.newFolder("spec-images")) { client }
            val markdown = viewModel(row).let { vm -> withTimeout(10_000) { vm.state.first { it.loaded } }.let { vm.markdown()!! } }
            val intent = ShareBundle.intent(context, 1, markdown) { images.get(row.serverId, it) }
            assertThat(intent.action).isEqualTo(android.content.Intent.ACTION_SEND)
            assertThat(intent.type).isEqualTo("application/zip")
            assertThat(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
            assertThat(uri.authority).isEqualTo("${context.packageName}.share")
            val names =
                java.util.zip.ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
                    generateSequence { zip.nextEntry }.map { it.name }.toList()
                }
            assertThat(names).containsExactly("spec-v1.md", "images/$region.png")
            // Without images the share stays the text share.
            val text = ShareBundle.intent(context, 1, "# Title\n") { null }
            assertThat(text.type).isEqualTo("text/markdown")
            assertThat(text.getStringExtra(android.content.Intent.EXTRA_TEXT)).isEqualTo("# Title\n")
        }
}

/** Unpair wipes documents, their specs and every frozen page and ink file. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = PairedApp::class, sdk = [35])
class DocumentsUnpairTest {
    @Test
    fun unpair_clears_documents() =
        runBlocking<Unit> {
            val container = (ApplicationProvider.getApplicationContext<App>()).container
            container.database.conversationDao().upsert(ConversationEntity("c1", "srv-1", "Doc", 0, 0))
            container.database.specDao().upsert(SpecEntity("srv-1", 1, "# x", "2026-11-04T10:00:00Z"))
            val page = RenderedPage(Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888), 0..1)
            container.annotationStore.freeze("c1", 1, listOf(page), DocumentLayout(20, 30))
            assertThat(container.annotationStore.frozen("c1", 1)).isNotNull()
            container.unpair()
            assertThat(
                container.database
                    .documentDao()
                    .observeDocuments()
                    .first(),
            ).isEmpty()
            assertThat(container.annotationStore.frozen("c1", 1)).isNull()
        }
}
