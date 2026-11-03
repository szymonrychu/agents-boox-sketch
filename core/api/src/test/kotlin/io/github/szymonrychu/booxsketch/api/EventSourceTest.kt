package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EventSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient
    private val conv = "11111111-1111-4111-8111-111111111111"
    private val store = MemoryCursors()

    private class MemoryCursors : CursorStore {
        val values = mutableMapOf<String, Long>()

        override suspend fun get(id: String) = values[id] ?: 0L

        override suspend fun set(
            id: String,
            cursor: Long,
        ) {
            values[id] = cursor
        }
    }

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

    private fun ev(c: Int) = """{"cursor":$c,"type":"agent_message","created_at":"2026-01-01T00:00:00Z","text":"m$c"}"""

    private fun sse(vararg cursors: Int) =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(cursors.joinToString("") { "id: $it\ndata: ${ev(it)}\n\n" })

    private fun poll(vararg cursors: Int) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"cursor":${cursors.lastOrNull() ?: 0},"events":[${cursors.joinToString(",") { ev(it) }}]}""")

    private fun events(
        scheduler: () -> Long = { System.currentTimeMillis() },
        backoff: Backoff = Backoff(1.seconds, 30.seconds, 0.0),
    ) = ConversationEvents(client, OkHttpClient(), store, scheduler, backoff)

    @Test
    fun resumes_from_stored_cursor_after_disconnect() =
        runBlocking {
            store.set(conv, 2)
            server.enqueue(sse(3, 4).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(poll(4, 5))
            val got = events().stream(conv).take(3).toList()
            assertThat(got.map { it.cursor }).containsExactly(3L, 4L, 5L).inOrder()
            assertThat(store.get(conv)).isEqualTo(5L)
            val first = server.takeRequest()
            assertThat(first.path).contains("/stream?after=2")
            assertThat(first.getHeader("Last-Event-ID")).isEqualTo("2")
            assertThat(server.takeRequest().path).contains("events?after=4&wait=25")
        }

    @Test
    fun falls_back_to_long_poll_after_45s_silence() =
        runTest {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(poll(1))
            val got = events({ currentTime }).stream(conv).take(1).toList()
            assertThat(got.map { it.cursor }).containsExactly(1L)
            assertThat(currentTime).isAtLeast(45_000L)
            val paths = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.path }.toList()
            assertThat(paths.last()).contains("/events?after=0&wait=25")
        }

    @Test
    fun long_poll_advances_cursor() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(poll(1, 2))
            server.enqueue(poll())
            server.enqueue(poll(3))
            val got = events().stream(conv).take(3).toList()
            assertThat(got.map { it.cursor }).containsExactly(1L, 2L, 3L).inOrder()
            assertThat(store.get(conv)).isEqualTo(3L)
            assertThat(server.takeRequest().path).contains("/stream")
            assertThat(server.takeRequest().path).contains("/events?after=0&wait=25")
            assertThat(server.takeRequest().path).contains("/events?after=2&wait=25")
            assertThat(server.takeRequest().path).contains("/events?after=2&wait=25")
        }

    @Test
    fun `401_terminates_flow_with_unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized","detail":"x"}"""))
        assertThrows(ApiError.Unauthorized::class.java) { runBlocking { events().stream(conv).toList() } }
    }
}
