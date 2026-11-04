package io.github.szymonrychu.booxsketch.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Change hints of the taskboard: SSE on `GET /tasks/stream` (`task_changed {key}`). Events are hints, nothing is stored.
 * After an SSE failure or end it emits [REFRESH] ("refresh the page") once, then reconnects with backoff capped at
 * [pollMs], so a dead stream degrades to a slow poll tick. `Unauthorized` propagates; `RateLimited` waits `Retry-After`.
 */
class TaskEvents(
    private val connector: ConnectorClient,
    http: OkHttpClient,
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val backoff: Backoff = Backoff(base = 1.seconds, cap = pollMs.milliseconds, jitter = 0.2),
    private val idleMs: Long = IDLE_MS,
) {
    private val sseHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** Task keys that changed; [REFRESH] when the page should be reloaded without a known key. */
    fun changes(): Flow<String> =
        flow {
            var attempt = 0
            while (true) {
                try {
                    readSse { attempt = 0 }
                } catch (e: ApiError.Unauthorized) {
                    throw e
                } catch (e: ApiError.RateLimited) {
                    delay(e.retryAfterS?.seconds ?: backoff.delayFor(attempt++))
                    continue
                } catch (_: ApiError) {
                    // falls through to a refresh hint and a reconnect
                } catch (_: IOException) {
                    // falls through to a refresh hint and a reconnect
                }
                emit(REFRESH)
                delay(backoff.delayFor(attempt++))
            }
        }

    private suspend fun FlowCollector<String>.readSse(onConnected: () -> Unit) =
        coroutineScope {
            val request =
                connector
                    .authedRequest("/tasks/stream")
                    .header("Accept", "text/event-stream")
                    .get()
                    .build()
            val call = sseHttp.newCall(request)
            val items = Channel<Result<SseItem>>(Channel.UNLIMITED)
            val reader = launch(Dispatchers.IO) { pumpSse(call, items) }
            try {
                while (true) {
                    val received = withTimeoutOrNull(idleMs) { items.receiveCatching() } ?: return@coroutineScope
                    val item = (received.getOrNull() ?: return@coroutineScope).getOrThrow()
                    onConnected()
                    if (item is SseItem.Message && item.event == EVENT_NAME) keyOf(item.data)?.let { emit(it) }
                }
            } finally {
                call.cancel()
                reader.cancel()
            }
        }

    private fun keyOf(data: String): String? {
        val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        return (obj["key"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Emitted when the stream was lost: reload the visible page. */
        const val REFRESH = ""
        const val DEFAULT_POLL_MS = 30_000L
        private const val EVENT_NAME = "task_changed"

        // the taskboard sends a keepalive comment every 15 s; three missed ones mean a dead connection
        const val IDLE_MS = 45_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
