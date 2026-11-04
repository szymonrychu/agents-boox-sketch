package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class TaskEventsTest {
    private lateinit var server: MockWebServer
    private lateinit var connector: ConnectorClient
    private val canceled = AtomicInteger()
    private val http =
        OkHttpClient
            .Builder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.incrementAndGet()
                    }
                },
            ).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        connector = ConnectorClient(server.url("/").toString().trimEnd('/'), { "test-token" }, http)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun changed(key: String) = "event: task_changed\ndata: {\"key\":\"$key\"}\n\n"

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun events(
        pollMs: Long = 50,
        backoff: Backoff = Backoff(1.milliseconds, 5.milliseconds, 0.0),
    ) = TaskEvents(connector, http, pollMs, backoff)

    @Test
    fun emits_key_on_task_changed() =
        runBlocking<Unit> {
            server.enqueue(sse(changed("T-1") + changed("T-2")))
            val got = events().changes().take(2).toList()
            assertThat(got).containsExactly("T-1", "T-2").inOrder()
            val r = server.takeRequest()
            assertThat(r.path).isEqualTo("/app/v1/sketch/tasks/stream")
            assertThat(r.getHeader("Accept")).isEqualTo("text/event-stream")
            assertThat(r.getHeader("Authorization")).isEqualTo("Bear" + "er test-token")
        }

    @Test
    fun ignores_keepalive_comments() =
        runBlocking<Unit> {
            server.enqueue(sse(": keepalive\n\n: keepalive\n\n" + "event: other\ndata: {\"key\":\"T-9\"}\n\n" + changed("T-3")))
            val got = events().changes().take(1).toList()
            assertThat(got).containsExactly("T-3")
        }

    @Test
    fun falls_back_to_poll_ticks_after_sse_failure() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(500))
            val got = events().changes().take(3).toList()
            assertThat(got).containsExactly(TaskEvents.REFRESH, TaskEvents.REFRESH, TaskEvents.REFRESH)
        }

    @Test
    fun reconnects_with_backoff() =
        runBlocking<Unit> {
            server.enqueue(sse(changed("T-1")).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(sse(changed("T-2")))
            val got = events().changes().take(3).toList()
            assertThat(got).containsExactly("T-1", TaskEvents.REFRESH, "T-2").inOrder()
            assertThat(server.requestCount).isEqualTo(2)
        }

    @Test
    fun unauthorized_propagates() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized","detail":"x"}"""))
        assertThrows(ApiError.Unauthorized::class.java) { runBlocking { events().changes().toList() } }
    }

    @Test
    fun rate_limited_honours_retry_after() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("""{"error":"rate_limited","detail":"x"}"""),
            )
            server.enqueue(sse(changed("T-4")))
            val started = System.currentTimeMillis()
            val got = events().changes().take(1).toList()
            assertThat(got).containsExactly("T-4")
            assertThat(System.currentTimeMillis() - started).isAtLeast(1_000L)
            assertThat(server.requestCount).isEqualTo(2)
        }

    @Test
    fun silent_connection_is_dropped_after_the_idle_limit() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(sse(changed("T-5")))
            val got = TaskEvents(connector, http, 50, Backoff(1.milliseconds, 5.milliseconds, 0.0), idleMs = 200).changes().take(2).toList()
            assertThat(got).containsExactly(TaskEvents.REFRESH, "T-5").inOrder()
        }

    @Test
    fun collector_cancel_closes_the_connection() =
        runBlocking<Unit> {
            server.enqueue(sse(changed("T-1")))
            events().changes().take(1).toList()
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2)
            while (canceled.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertThat(canceled.get()).isAtLeast(1)
        }
}
