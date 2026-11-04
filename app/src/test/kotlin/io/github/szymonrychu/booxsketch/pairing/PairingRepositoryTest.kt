package io.github.szymonrychu.booxsketch.pairing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PairingRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val auth = mutableListOf<String?>()

    private fun store() =
        TokenStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "p.preferences_pb") }, FakeKeyProvider())

    private fun repo(
        store: TokenStore,
        allowLoopbackHttp: Boolean = false,
        reply: (Interceptor.Chain) -> Response,
    ): PairingRepository {
        val http =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests += chain.request().url.toString()
                    bodies +=
                        chain
                            .request()
                            .body
                            ?.let { b -> okio.Buffer().also { buf -> b.writeTo(buf) }.readUtf8() }
                            .orEmpty()
                    auth += chain.request().header("Authorization")
                    reply(chain)
                }.build()
        return PairingRepository(store, { origin, token -> ConnectorClient(origin, token, http) }, allowLoopbackHttp)
    }

    private fun answer(
        chain: Interceptor.Chain,
        code: Int,
        body: String = "{}",
    ): Response =
        Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("m")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private val pairedJson = """{"device_id":"d1","name":"Tab","plugin":"sketch","token":"test-token"}"""
    private val configJson =
        """{"max_long_edge_px":1,"max_png_bytes":2,"max_strokes_bytes":3,"max_pages":4,""" +
            """"max_upload_bytes":5,"min_edge_px":6,"api_version":1}"""

    /** Replies like the connector: `/pair` gives the token, `/config` the limits. */
    private fun server(chain: Interceptor.Chain): Response =
        if (chain
                .request()
                .url.encodedPath
                .endsWith("/pair")
        ) {
            answer(chain, 200, pairedJson)
        } else {
            answer(chain, 200, configJson)
        }

    @Test
    fun rejects_http_origin() =
        runBlocking {
            val r = repo(store()) { server(it) }
            assertThat(r.pair("http://connector.example", "123456")).isEqualTo(PairResult.InvalidOrigin)
            assertThat(r.pair("http://127.0.0.1:8080", "123456")).isEqualTo(PairResult.InvalidOrigin)
            assertThat(requests).isEmpty()
        }

    @Test
    fun loopback_http_allowed_only_when_flag_set() =
        runBlocking {
            val r = repo(store(), allowLoopbackHttp = true) { server(it) }
            assertThat(r.pair("http://127.0.0.1:8080", "123456")).isInstanceOf(PairResult.Ok::class.java)
            assertThat(r.pair("http://connector.example", "123456")).isEqualTo(PairResult.InvalidOrigin)
        }

    @Test
    fun rejects_origin_with_path_or_other_junk() =
        runBlocking {
            val r = repo(store()) { server(it) }
            for (bad in listOf(
                "https://connector.example/app",
                "https://connector.example?x=1",
                "https://connector.example#f",
                "connector.example/app",
                "connector.example?x=1",
                "ftp://connector.example",
                "https://user@connector.example",
                "https://",
                "not a host",
                "connector_example",
                "",
                "   ",
            )) {
                assertThat(r.pair(bad, "123456")).isEqualTo(PairResult.InvalidOrigin)
            }
            assertThat(requests).isEmpty()
        }

    @Test
    fun bare_host_gets_https() =
        runBlocking {
            val s = store()
            val r = repo(s) { server(it) }
            assertThat(r.pair("connector.example", "123456")).isInstanceOf(PairResult.Ok::class.java)
            assertThat(s.origin()).isEqualTo("https://connector.example")
            assertThat(requests.first()).isEqualTo("https://connector.example/app/v1/sketch/pair")
            assertThat(r.pair(" connector.example:8443/ ", "123456")).isInstanceOf(PairResult.Ok::class.java)
            assertThat(s.origin()).isEqualTo("https://connector.example:8443")
        }

    @Test
    fun malformed_code_is_rejected_locally_without_a_request() =
        runBlocking {
            val r = repo(store()) { server(it) }
            for (bad in listOf("", "12345", "1234567", "12345a", "12 456")) {
                assertThat(r.pair("https://connector.example", bad)).isEqualTo(PairResult.CodeRejected)
            }
            assertThat(requests).isEmpty()
        }

    @Test
    fun invalid_code_gives_code_rejected_and_stores_nothing() =
        runBlocking {
            val s = store()
            val r = repo(s) { answer(it, 400, """{"error":"invalid_code","detail":"x"}""") }
            assertThat(r.pair("https://connector.example", "123456")).isEqualTo(PairResult.CodeRejected)
            assertThat(s.token()).isNull()
            assertThat(s.origin()).isNull()
            assertThat(requests).hasSize(1)
        }

    @Test
    fun malformed_body_422_gives_code_rejected() =
        runBlocking {
            val r = repo(store()) { answer(it, 422, """{"error":"invalid_request","detail":"x"}""") }
            assertThat(r.pair("https://connector.example", "123456")).isEqualTo(PairResult.CodeRejected)
        }

    @Test
    fun rate_limit_gives_rate_limited_with_wait_and_stores_nothing() =
        runBlocking {
            val s = store()
            val r =
                repo(s) {
                    answer(it, 429, """{"error":"rate_limited","detail":"x"}""").newBuilder().header("Retry-After", "30").build()
                }
            assertThat(r.pair("https://connector.example", "123456")).isEqualTo(PairResult.RateLimited(30))
            assertThat(s.token()).isNull()
        }

    @Test
    fun network_failure_gives_unreachable() =
        runBlocking {
            val r = repo(store()) { throw IOException("boom") }
            assertThat(r.pair("https://connector.example", "123456")).isInstanceOf(PairResult.Unreachable::class.java)
        }

    @Test
    fun exchange_then_config_proof_then_store() =
        runBlocking {
            val s = store()
            val r = repo(s) { server(it) }
            val result = r.pair("https://connector.example/", "123456")
            assertThat(result).isInstanceOf(PairResult.Ok::class.java)
            assertThat(s.token()).isEqualTo("test-token")
            assertThat(s.origin()).isEqualTo("https://connector.example")
            assertThat(requests)
                .containsExactly("https://connector.example/app/v1/sketch/pair", "https://connector.example/app/v1/sketch/config")
                .inOrder()
            assertThat(bodies[0]).isEqualTo("""{"code":"123456"}""")
            assertThat(auth[0]).isNull()
            assertThat(auth[1]).isEqualTo("Bear" + "er test-token")
        }

    @Test
    fun failing_config_proof_after_exchange_stores_nothing() =
        runBlocking {
            val s = store()
            val r =
                repo(s) {
                    if (it.request().url.encodedPath.endsWith(
                            "/pair",
                        )
                    ) {
                        answer(it, 200, pairedJson)
                    } else {
                        answer(it, 401, """{"error":"unauthorized","detail":"x"}""")
                    }
                }
            assertThat(r.pair("https://connector.example", "123456")).isInstanceOf(PairResult.Unreachable::class.java)
            assertThat(s.token()).isNull()
        }

    @Test
    fun storage_failure_gives_storage_failed_not_a_throw() =
        runBlocking {
            val failing =
                object : KeyProvider {
                    override fun key(): javax.crypto.SecretKey = throw java.security.GeneralSecurityException("keystore gone")
                }
            val s = TokenStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "f.preferences_pb") }, failing)
            val r = repo(s) { server(it) }
            val result = r.pair("https://connector.example", "123456")
            assertThat(result).isInstanceOf(PairResult.StorageFailed::class.java)
            assertThat(pairingErrorText(result)).isNotNull()
            assertThat(s.token()).isNull()
        }

    @Test
    fun unchecked_keystore_failure_gives_storage_failed() =
        runBlocking {
            val failing =
                object : KeyProvider {
                    override fun key(): javax.crypto.SecretKey = throw java.security.ProviderException("keystore broken")
                }
            val s = TokenStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "u.preferences_pb") }, failing)
            val r = repo(s) { server(it) }
            val result = r.pair("https://connector.example", "123456")
            assertThat(result).isInstanceOf(PairResult.StorageFailed::class.java)
            assertThat(s.token()).isNull()
        }

    @Test
    fun reject_token_delegates_to_store() =
        runBlocking {
            val s = store()
            val r = repo(s) { server(it) }
            r.pair("https://connector.example", "123456")
            r.rejectToken()
            assertThat(s.token()).isNull()
            assertThat(s.originFlow.value).isEqualTo("https://connector.example")
        }

    @Test
    fun error_texts_never_contain_the_code_and_name_the_cause() {
        assertThat(pairingErrorText(PairResult.CodeRejected)).contains("expired")
        assertThat(pairingErrorText(PairResult.CodeRejected)).contains("locked")
        assertThat(pairingErrorText(PairResult.RateLimited(30))).contains("30")
        assertThat(pairingErrorText(PairResult.RateLimited(null))).contains("Too many")
        assertThat(pairingErrorText(PairResult.Ok(ConfigDto(1, 2, 3, 4, 5, 6, 1)))).isNull()
    }
}
