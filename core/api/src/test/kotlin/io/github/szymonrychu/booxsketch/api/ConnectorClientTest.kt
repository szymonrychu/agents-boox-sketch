package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConnectorClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private val bearerHeader = "Bear" + "er test-token"
    private val conv = "11111111-1111-4111-8111-111111111111"
    private val cid = "22222222-2222-4222-8222-222222222222"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ConnectorClient(server.url("/").toString().trimEnd('/'), { "test-token" }, OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(
        body: String,
        code: Int = 200,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun pair_posts_code_without_authorization_and_parses_token() =
        runTest {
            server.enqueue(json("""{"device_id":"d1","name":"Tab","plugin":"sketch","token":"test-token"}"""))
            val paired = client.pair("123456")
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("POST")
            assertThat(r.path).isEqualTo("/app/v1/sketch/pair")
            assertThat(r.getHeader("Authorization")).isNull()
            assertThat(
                Json
                    .parseToJsonElement(r.body.readUtf8())
                    .jsonObject["code"]!!
                    .jsonPrimitive.content,
            ).isEqualTo("123456")
            assertThat(paired.token).isEqualTo("test-token")
            assertThat(paired.deviceId).isEqualTo("d1")
        }

    @Test
    fun pair_maps_400_to_invalid_code_and_429_to_rate_limited() =
        runTest {
            server.enqueue(json("""{"error":"invalid_code","detail":"x"}""", 400))
            server.enqueue(json("""{"error":"rate_limited","detail":"x"}""", 429).setHeader("Retry-After", "42"))
            server.enqueue(json("""{"error":"invalid_request","detail":"x"}""", 422))
            val e1 = runCatching { client.pair("123456") }.exceptionOrNull()
            val e2 = runCatching { client.pair("123456") }.exceptionOrNull()
            val e3 = runCatching { client.pair("123456") }.exceptionOrNull()
            assertThat(e1).isEqualTo(ApiError.InvalidCode)
            assertThat(e2).isEqualTo(ApiError.RateLimited(42))
            assertThat(e3).isInstanceOf(ApiError.Invalid::class.java)
        }

    @Test
    fun config_parses_limits() =
        runTest {
            server.enqueue(
                json(
                    """{"max_long_edge_px":1568,"max_png_bytes":2000000,"max_strokes_bytes":500000,
                    "max_pages":4,"max_upload_bytes":8000000,"min_edge_px":200,"api_version":1}""",
                ),
            )
            val c = client.config()
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("GET")
            assertThat(r.path).isEqualTo("/app/v1/sketch/config")
            assertThat(r.getHeader("Authorization")).isEqualTo(bearerHeader)
            assertThat(c.maxPages).isEqualTo(4)
            assertThat(c.maxUploadBytes).isEqualTo(8000000)
            assertThat(c.minEdgePx).isEqualTo(200)
            assertThat(c.apiVersion).isEqualTo(1)
        }

    @Test
    fun uploadSketch_sends_multipart_parts() =
        runTest {
            server.enqueue(json("""{"sketch_id":"$cid","conversation_id":"$conv","status":"queued"}""", 202))
            val meta =
                SketchMetaDto(
                    clientId = cid,
                    conversationId = conv,
                    pages = listOf(PageMetaDto(0, Canvas(1404, 1872, 300f)), PageMetaDto(1, Canvas(1404, 1872, 300f))),
                )
            val result = client.uploadSketch(meta, listOf(byteArrayOf(1, 2), byteArrayOf(3)), listOf(null, "{}".toByteArray()))
            val r = server.takeRequest()
            val body = r.body.readUtf8()
            assertThat(r.method).isEqualTo("POST")
            assertThat(r.path).isEqualTo("/app/v1/sketch/sketches")
            assertThat(r.getHeader("Content-Type")).startsWith("multipart/form-data")
            assertThat(body).contains("name=\"meta\"")
            assertThat(body).contains("name=\"sketch.0.png\"")
            assertThat(body).contains("name=\"sketch.1.png\"")
            assertThat(body).contains("name=\"strokes.1.json\"")
            assertThat(body).doesNotContain("strokes.0.json")
            assertThat(body).contains("\"client_id\":\"$cid\"")
            assertThat(body).contains("\"conversation_id\":\"$conv\"")
            assertThat(body).contains("\"pages\":[{\"page\":0")
            assertThat(result.conversationId).isEqualTo(conv)
            assertThat(result.status).isEqualTo("queued")
        }

    @Test
    fun uploadSketch_meta_encodes_defaults_and_omits_absent_optionals() =
        runTest {
            server.enqueue(json("""{"sketch_id":"$cid","conversation_id":"$conv","status":"queued"}""", 202))
            val meta = SketchMetaDto(clientId = cid, pages = listOf(PageMetaDto(0, Canvas(1, 1, 300f))))
            client.uploadSketch(meta, listOf(byteArrayOf(1)), listOf(null))
            val body = server.takeRequest().body.readUtf8()
            val metaJson = body.lineSequence().first { it.startsWith("{\"client_id\"") }
            val obj = Json.parseToJsonElement(metaJson).jsonObject
            assertThat(obj["conversation"]?.jsonPrimitive?.content).isEqualTo("current")
            assertThat(obj.keys).containsExactly("client_id", "conversation", "pages")
            val canvas = obj["pages"]!!.jsonArray[0].jsonObject["canvas"]!!.jsonObject
            assertThat(canvas.keys).containsExactly("w", "h", "dpi")
        }

    @Test
    fun uploadSketch_accepts_200_replay() =
        runTest {
            server.enqueue(json("""{"sketch_id":"$cid","conversation_id":"$conv","status":"queued"}""", 200))
            val meta = SketchMetaDto(clientId = cid, pages = listOf(PageMetaDto(0, Canvas(1, 1, 300f))))
            assertThat(client.uploadSketch(meta, listOf(byteArrayOf(1)), listOf(null)).sketchId).isEqualTo(cid)
        }

    @Test
    fun postMessage_returns_message_id() =
        runTest {
            server.enqueue(json("""{"message_id":"$cid"}""", 202))
            val id = client.postMessage(conv, cid, "hello")
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("POST")
            assertThat(r.path).isEqualTo("/app/v1/sketch/conversations/$conv/messages")
            val sent = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
            assertThat(sent["client_id"]!!.jsonPrimitive.content).isEqualTo(cid)
            assertThat(sent["text"]!!.jsonPrimitive.content).isEqualTo("hello")
            assertThat(id).isEqualTo(cid)
        }

    @Test
    fun conversations_lists_rows() =
        runTest {
            server.enqueue(
                json(
                    """[{"id":"$conv","title":null,"created_at":"2026-11-03T10:00:00Z","latest_spec_version":2},
                    {"id":"$cid","title":"t","created_at":"2026-11-03T11:00:00Z","latest_spec_version":null}]""",
                ),
            )
            val rows = client.conversations()
            assertThat(server.takeRequest().path).isEqualTo("/app/v1/sketch/conversations")
            assertThat(rows).hasSize(2)
            assertThat(rows[0].latestSpecVersion).isEqualTo(2)
            assertThat(rows[1].title).isEqualTo("t")
        }

    @Test
    fun createConversation_returns_id() =
        runTest {
            server.enqueue(json("""{"id":"$conv"}""", 201))
            assertThat(client.createConversation()).isEqualTo(conv)
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("POST")
            assertThat(r.path).isEqualTo("/app/v1/sketch/conversations")
        }

    @Test
    fun deleteConversation_sends_delete() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(204))
            client.deleteConversation(conv)
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("DELETE")
            assertThat(r.path).isEqualTo("/app/v1/sketch/conversations/$conv")
        }

    @Test
    fun events_sends_cursor_and_flattens_payload() =
        runTest {
            server.enqueue(
                json(
                    """{"cursor":7,"events":[{"cursor":6,"type":"message","created_at":"2026-11-03T10:00:00Z","text":"hi"},
                    {"cursor":7,"type":"spec_ready","created_at":"2026-11-03T10:01:00Z","version":2}]}""",
                ),
            )
            val events = client.events(conv, after = 5, waitS = 20)
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("GET")
            assertThat(r.path).isEqualTo("/app/v1/sketch/conversations/$conv/events?after=5&wait=20")
            assertThat(events.map { it.cursor }).containsExactly(6L, 7L).inOrder()
            assertThat(events[0].type).isEqualTo("message")
            assertThat(events[0].payload["text"]!!.jsonPrimitive.content).isEqualTo("hi")
            assertThat(events[0].payload).doesNotContainKey("cursor")
        }

    @Test
    fun events_long_poll_outlives_the_shared_client_read_timeout() =
        runTest {
            val shortClient = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
            val slow = ConnectorClient(server.url("/").toString().trimEnd('/'), { "test-token" }, shortClient)
            server.enqueue(json("""{"cursor":0,"events":[]}""").setBodyDelay(1, TimeUnit.SECONDS))
            assertThat(slow.events(conv, after = 0, waitS = 1)).isEmpty()
        }

    @Test
    fun spec_latest_and_versioned() =
        runTest {
            val spec = """{"version":2,"markdown":"# S","created":"2026-11-03T10:00:00Z","sketch_ids":["$cid"]}"""
            server.enqueue(json(spec))
            server.enqueue(json(spec))
            assertThat(client.spec(conv, null).markdown).isEqualTo("# S")
            assertThat(server.takeRequest().path).isEqualTo("/app/v1/sketch/conversations/$conv/spec")
            assertThat(client.spec(conv, 2).sketchIds).containsExactly(cid)
            assertThat(server.takeRequest().path).isEqualTo("/app/v1/sketch/conversations/$conv/spec/2")
        }

    @Test
    fun specMarkdown_returns_text() =
        runTest {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/markdown").setBody("# Spec\n"))
            assertThat(client.specMarkdown(conv)).isEqualTo("# Spec\n")
            assertThat(server.takeRequest().path).isEqualTo("/app/v1/sketch/conversations/$conv/spec.md")
        }

    @Test
    fun handoffTargets_parses_sessions_and_presets() =
        runTest {
            server.enqueue(json("""{"sessions":[{"id":"s1","slug":"alpha","preset_name":null}],"presets":["dev"]}"""))
            val t = client.handoffTargets()
            assertThat(server.takeRequest().path).isEqualTo("/app/v1/sketch/handoff/targets")
            assertThat(t.sessions.single().slug).isEqualTo("alpha")
            assertThat(t.presets).containsExactly("dev")
        }

    @Test
    fun handoff_posts_session_slug_or_preset() =
        runTest {
            server.enqueue(json("""{"handoff_id":"$cid","session_slug":"alpha"}""", 202))
            server.enqueue(json("""{"handoff_id":"$cid","session_slug":"beta"}""", 202))
            client.handoff(conv, HandoffDto.toSession(cid, "alpha"))
            client.handoff(conv, HandoffDto.toPreset(cid, "dev"))
            val first = server.takeRequest()
            assertThat(first.path).isEqualTo("/app/v1/sketch/conversations/$conv/handoff")
            assertThat(first.body.readUtf8()).isEqualTo("""{"client_id":"$cid","session":"alpha"}""")
            assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"client_id":"$cid","session":{"preset":"dev"}}""")
        }

    @Test
    fun authedRequest_has_url_and_bearer_header() {
        val request = client.authedRequest("/conversations/$conv/stream").build()
        assertThat(request.url.encodedPath).isEqualTo("/app/v1/sketch/conversations/$conv/stream")
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.header("Authorization")).isEqualTo(bearerHeader)
    }

    @Test
    fun authedRequest_omits_header_without_token() {
        val anonymous = ConnectorClient("https://connector.example", { null }, OkHttpClient())
        val request = anonymous.authedRequest("/config").build()
        assertThat(request.url.toString()).isEqualTo("https://connector.example/app/v1/sketch/config")
        assertThat(request.header("Authorization")).isNull()
    }
}
