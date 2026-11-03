package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test

class ErrorMappingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ConnectorClient

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

    private fun failure(
        status: Int,
        code: String,
        retryAfter: String? = null,
    ): MockResponse {
        val r = MockResponse().setResponseCode(status).setBody("""{"error":"$code","detail":"d"}""")
        if (retryAfter != null) r.setHeader("Retry-After", retryAfter)
        return r
    }

    private suspend fun errorOf(response: MockResponse): ApiError {
        server.enqueue(response)
        return try {
            client.config()
            error("expected an ApiError")
        } catch (e: ApiError) {
            e
        }
    }

    @Test
    fun maps_each_status_and_code() =
        runTest {
            val table =
                listOf(
                    failure(401, "unauthorized") to ApiError.Unauthorized,
                    failure(404, "not_found") to ApiError.NotFound("not_found"),
                    failure(409, "in_progress") to ApiError.InProgress,
                    failure(409, "no_spec") to ApiError.Conflict("no_spec"),
                    failure(413, "too_large") to ApiError.TooLarge("too_large"),
                    failure(413, "quota_exceeded") to ApiError.TooLarge("quota_exceeded"),
                    failure(415, "unsupported") to ApiError.Unsupported,
                    failure(422, "idempotency_mismatch") to ApiError.Invalid("idempotency_mismatch", "d"),
                    failure(422, "invalid_request") to ApiError.Invalid("invalid_request", "d"),
                    failure(429, "rate_limited", "7") to ApiError.RateLimited(7L),
                    failure(429, "rate_limited") to ApiError.RateLimited(null),
                    failure(500, "internal") to ApiError.Server(500),
                    failure(503, "unavailable") to ApiError.Server(503),
                )
            for ((response, expected) in table) {
                assertThat(errorOf(response)).isEqualTo(expected)
            }
        }

    @Test
    fun non_json_error_body_still_maps_by_status() =
        runTest {
            val e = errorOf(MockResponse().setResponseCode(404).setBody("<html>nope</html>"))
            assertThat(e).isEqualTo(ApiError.NotFound("unknown"))
        }

    @Test
    fun io_failure_maps_to_network() =
        runTest {
            val e = errorOf(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertThat(e).isInstanceOf(ApiError.Network::class.java)
            assertThat(e.cause).isNotNull()
        }
}
