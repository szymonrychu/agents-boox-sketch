package io.github.szymonrychu.booxsketch.pairing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
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

    private val configJson =
        """{"max_long_edge_px":1,"max_png_bytes":2,"max_strokes_bytes":3,"max_pages":4,""" +
            """"max_upload_bytes":5,"min_edge_px":6,"api_version":1}"""

    @Test
    fun rejects_http_origin() =
        runBlocking {
            val r = repo(store()) { answer(it, 200, configJson) }
            assertThat(r.pair("http://connector.example", "test-token")).isEqualTo(PairResult.InvalidOrigin)
            assertThat(r.pair("http://127.0.0.1:8080", "test-token")).isEqualTo(PairResult.InvalidOrigin)
            assertThat(requests).isEmpty()
        }

    @Test
    fun loopback_http_allowed_only_when_flag_set() =
        runBlocking {
            val r = repo(store(), allowLoopbackHttp = true) { answer(it, 200, configJson) }
            assertThat(r.pair("http://10.0.2.2:8080", "test-token")).isInstanceOf(PairResult.Ok::class.java)
            assertThat(r.pair("http://connector.example", "test-token")).isEqualTo(PairResult.InvalidOrigin)
        }

    @Test
    fun rejects_origin_with_path() =
        runBlocking {
            val r = repo(store()) { answer(it, 200, configJson) }
            for (bad in listOf(
                "https://connector.example/app",
                "https://connector.example?x=1",
                "https://connector.example#f",
                "https://",
                "connector.example",
                "",
            )) {
                assertThat(r.pair(bad, "test-token")).isEqualTo(PairResult.InvalidOrigin)
            }
            assertThat(requests).isEmpty()
        }

    @Test
    fun `401_gives_token_rejected`() =
        runBlocking {
            val s = store()
            val r = repo(s) { answer(it, 401, """{"error":"unauthorized"}""") }
            assertThat(r.pair("https://connector.example", "test-token")).isEqualTo(PairResult.TokenRejected)
            assertThat(s.token()).isNull()
            assertThat(s.origin()).isNull()
        }

    @Test
    fun network_failure_gives_unreachable() =
        runBlocking {
            val r = repo(store()) { throw IOException("boom") }
            assertThat(r.pair("https://connector.example", "test-token")).isInstanceOf(PairResult.Unreachable::class.java)
        }

    @Test
    fun `200_stores_both`() =
        runBlocking {
            val s = store()
            val r = repo(s) { answer(it, 200, configJson) }
            val result = r.pair("https://connector.example/", "test-token")
            assertThat(result).isInstanceOf(PairResult.Ok::class.java)
            assertThat(s.token()).isEqualTo("test-token")
            assertThat(s.origin()).isEqualTo("https://connector.example")
            assertThat(requests.single()).isEqualTo("https://connector.example/app/v1/sketch/config")
            assertThat(auth.single()).isEqualTo("Bear" + "er test-token")
        }

    @Test
    fun storage_failure_gives_storage_failed_not_a_throw() =
        runBlocking {
            val failing =
                object : KeyProvider {
                    override fun key(): javax.crypto.SecretKey = throw java.security.GeneralSecurityException("keystore gone")
                }
            val s = TokenStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "f.preferences_pb") }, failing)
            val r = repo(s) { answer(it, 200, configJson) }
            val result = r.pair("https://connector.example", "test-token")
            assertThat(result).isInstanceOf(PairResult.StorageFailed::class.java)
            assertThat(pairingErrorText(result)).isNotNull()
            assertThat(s.token()).isNull()
        }

    @Test
    fun reject_token_delegates_to_store() =
        runBlocking {
            val s = store()
            val r = repo(s) { answer(it, 200, configJson) }
            r.pair("https://connector.example", "test-token")
            r.rejectToken()
            assertThat(s.token()).isNull()
            assertThat(s.originFlow.value).isEqualTo("https://connector.example")
        }
}
