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
                    failure(410, "purged") to ApiError.Purged,
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

    private fun nested(
        status: Int,
        code: String,
        message: String = "m",
    ) = MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code","message":"$message"}}""")

    @Test
    fun maps_412_to_stale_version() =
        runTest {
            assertThat(errorOf(nested(412, "stale_version"))).isEqualTo(ApiError.StaleVersion)
        }

    @Test
    fun maps_409_already_answered() =
        runTest {
            assertThat(errorOf(nested(409, "already_answered"))).isEqualTo(ApiError.AlreadyAnswered)
            assertThat(errorOf(failure(409, "no_spec"))).isEqualTo(ApiError.Conflict("no_spec"))
        }

    @Test
    fun maps_503_codes_to_unavailable() =
        runTest {
            assertThat(errorOf(failure(503, "taskboard_unavailable"))).isEqualTo(ApiError.Unavailable("taskboard_unavailable"))
            assertThat(errorOf(failure(503, "taskboard_unconfigured"))).isEqualTo(ApiError.Unavailable("taskboard_unconfigured"))
        }

    @Test
    fun parses_taskboard_nested_error_body() =
        runTest {
            assertThat(errorOf(nested(422, "invalid_request", "title too long")))
                .isEqualTo(ApiError.Invalid("invalid_request", "title too long"))
            assertThat(errorOf(nested(404, "not_found"))).isEqualTo(ApiError.NotFound("not_found"))
        }

    @Test
    fun parses_connector_flat_error_body() =
        runTest {
            assertThat(errorOf(failure(422, "invalid_request"))).isEqualTo(ApiError.Invalid("invalid_request", "d"))
            assertThat(parseErrorBody("""{"error":"x","detail":"y"}""")).isEqualTo("x" to "y")
            assertThat(parseErrorBody("""{"error":{"code":"x","message":"y"}}""")).isEqualTo("x" to "y")
            assertThat(parseErrorBody("[]")).isNull()
        }
}
