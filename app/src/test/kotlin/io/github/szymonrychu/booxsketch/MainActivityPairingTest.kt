package io.github.szymonrychu.booxsketch

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState
import io.github.szymonrychu.booxsketch.board.TAG_SEND
import io.github.szymonrychu.booxsketch.data.OutboxEntity
import io.github.szymonrychu.booxsketch.pairing.FakeKeyProvider
import io.github.szymonrychu.booxsketch.pairing.TAG_ORIGIN
import io.github.szymonrychu.booxsketch.pairing.TAG_PAIR
import io.github.szymonrychu.booxsketch.pairing.keyTag
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class UnpairedApp : App() {
    override fun newContainer(): AppContainer = AppContainer(this, keyProvider = FakeKeyProvider())
}

private const val CONFIG_JSON =
    """{"max_long_edge_px":1,"max_png_bytes":2,"max_strokes_bytes":3,"max_pages":4,""" +
        """"max_upload_bytes":5,"min_edge_px":6,"api_version":1}"""

private const val PAIRED_JSON = """{"device_id":"d1","name":"Tab","plugin":"sketch","token":"fresh-token"}"""

/** Paired app whose server answers `POST /pair` and `GET /config` with 200 and counts [repaired] calls. */
class RepairableApp : App() {
    override fun newContainer(): AppContainer =
        AppContainer(
            this,
            keyProvider = FakeKeyProvider(),
            httpFactory = {
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val body =
                            if (chain
                                    .request()
                                    .url.encodedPath
                                    .endsWith("/pair")
                            ) {
                                PAIRED_JSON
                            } else {
                                CONFIG_JSON
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("ok")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    }.build()
            },
            onRepaired = { repaired++ },
        ).also { runBlocking { it.tokenStore.save("https://connector.example", "test-token") } }

    companion object {
        @Volatile var repaired = 0
    }
}

@RunWith(AndroidJUnit4::class)
@Config(application = RepairableApp::class, sdk = [35])
class MainActivityRepairTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun reset() {
        RepairableApp.repaired = 0
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun runtime_rejected_token_shows_pairing_keeps_rooms_and_repair_resumes() {
        val container = (compose.activity.application as App).container
        waitFor(TAG_SEND)
        runBlocking {
            container.database.outboxDao().insert(
                OutboxEntity(
                    clientId = "c1",
                    kind = OutboxKind.SKETCH,
                    conversationLocalId = "l1",
                    conversationServerId = null,
                    conversationMode = null,
                    payloadDir = "p",
                    state = OutboxState.PENDING,
                    attempts = 0,
                    nextAttemptAtMs = 0,
                    lastError = null,
                    serverId = null,
                ),
            )
            container.pairingRepository.rejectToken()
        }
        waitFor(TAG_PAIR)
        compose.onNodeWithTag(TAG_SEND).assertDoesNotExist()
        compose.onNodeWithTag(TAG_ORIGIN).assertTextContains("https://connector.example")
        assertThat(runBlocking { container.database.outboxDao().get(1) }).isNotNull()
        assertThat(RepairableApp.repaired).isEqualTo(0)

        "123456".forEach { compose.onNodeWithTag(keyTag(it)).performClick() }
        compose.onNodeWithTag(TAG_PAIR).performClick()
        waitFor(TAG_SEND)
        assertThat(RepairableApp.repaired).isEqualTo(1)
        assertThat(runBlocking { container.database.outboxDao().get(1) }).isNotNull()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(application = UnpairedApp::class, sdk = [35])
class MainActivityPairingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun unpaired_start_shows_pairing_instead_of_board() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(TAG_PAIR)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TAG_PAIR).assertExists()
        compose.onNodeWithTag(TAG_SEND).assertDoesNotExist()
    }
}
