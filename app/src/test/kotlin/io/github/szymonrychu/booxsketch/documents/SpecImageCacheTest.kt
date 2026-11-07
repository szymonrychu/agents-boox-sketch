package io.github.szymonrychu.booxsketch.documents

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpecImageCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()
    private val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
    private val region = "0b7f6a1e-2c3d-4e5f-8a9b-0c1d2e3f4a5b"

    @Before
    fun setUp() {
        server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.path.orEmpty()
                            paths += path
                            return if (path.endsWith("/images/$region.png")) {
                                MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                            } else {
                                MockResponse()
                                    .setResponseCode(404)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("""{"error":"not_found","detail":""}""")
                            }
                        }
                    }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun cache(online: Boolean = true) =
        SpecImageCache(File(tmp.root, "spec-images")) {
            if (online) ConnectorClient(server.url("/").toString(), { "test-token" }, OkHttpClient()) else null
        }

    @Test
    fun get_fetches_once_and_keeps_the_file() =
        runBlocking<Unit> {
            val c = cache()
            assertThat(c.cached("srv-1", region)).isNull()
            assertThat(c.get("srv-1", region)).isEqualTo(png)
            assertThat(c.get("srv-1", region)).isEqualTo(png)
            assertThat(paths.single()).endsWith("/conversations/srv-1/images/$region.png")
            assertThat(c.cached("srv-1", region)).isEqualTo(File(tmp.root, "spec-images/srv-1/$region.png"))
        }

    @Test
    fun offline_or_unknown_image_is_null_and_not_cached() =
        runBlocking<Unit> {
            assertThat(cache(online = false).get("srv-1", region)).isNull()
            val missing = "11111111-2222-4333-8444-555555555555"
            assertThat(cache().get("srv-1", missing)).isNull()
            assertThat(cache().cached("srv-1", missing)).isNull()
        }

    @Test
    fun ids_outside_the_reference_form_are_refused() =
        runBlocking<Unit> {
            assertThat(cache().get("srv-1", "../../etc/passwd")).isNull()
            assertThat(cache().get("../x", region)).isNull()
            assertThat(paths).isEmpty()
        }

    @Test
    fun delete_removes_the_conversation_images() =
        runBlocking<Unit> {
            val c = cache()
            c.get("srv-1", region)
            c.delete("srv-1")
            assertThat(c.cached("srv-1", region)).isNull()
            assertThat(File(tmp.root, "spec-images/srv-1").exists()).isFalse()
        }

    @Test
    fun ids_lists_the_image_references_in_order_once() {
        val md =
            "# T\n\n![image](images/$region.png)\n\ntext ![x](images/abc-1.png) and ![image](images/$region.png)\n\n" +
                "![web](https://example.invalid/a.png)"
        assertThat(SpecImageCache.ids(md)).containsExactly(region, "abc-1").inOrder()
    }
}
