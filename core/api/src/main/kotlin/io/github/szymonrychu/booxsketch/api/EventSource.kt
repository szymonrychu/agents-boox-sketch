package io.github.szymonrychu.booxsketch.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Persists the last delivered event cursor of a conversation. */
interface CursorStore {
    suspend fun get(id: String): Long

    suspend fun set(
        id: String,
        cursor: Long,
    )
}

/**
 * Event stream of one conversation: SSE first, long-poll for the rest of the flow after 45 s of silence or any SSE
 * failure. The cursor is stored after each emitted event; events at or below it are never emitted twice.
 */
class ConversationEvents(
    private val client: ConnectorClient,
    http: OkHttpClient,
    private val cursorStore: CursorStore,
    private val clock: () -> Long,
    private val backoff: Backoff = Backoff(base = 1.seconds, cap = 30.seconds, jitter = 0.2),
) {
    private val sseHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** Emits events of [conversationId] after the stored cursor. Fails with [ApiError.Unauthorized] on 401. */
    fun stream(conversationId: String): Flow<EventDto> =
        flow {
            var cursor = cursorStore.get(conversationId)
            val deliver: suspend FlowCollector<EventDto>.(EventDto) -> Unit = { event ->
                if (event.cursor > cursor) {
                    try {
                        emit(event)
                    } finally {
                        // the event reached the collector (or the collector stopped right after): never deliver it again
                        cursor = event.cursor
                        withContext(NonCancellable) { cursorStore.set(conversationId, cursor) }
                    }
                }
            }
            try {
                readSse(conversationId, cursor, deliver)
            } catch (e: ApiError.Unauthorized) {
                throw e
            } catch (e: ApiError.NotFound) {
                throw e
            } catch (_: ApiError) {
                // any other SSE failure falls through to long-poll
            } catch (_: IOException) {
                // any other SSE failure falls through to long-poll
            }
            var attempt = 0
            while (true) {
                try {
                    client.events(conversationId, cursor, LONG_POLL_WAIT_S).forEach { deliver(it) }
                    attempt = 0
                } catch (e: ApiError.Unauthorized) {
                    throw e
                } catch (e: ApiError.NotFound) {
                    throw e
                } catch (_: ApiError) {
                    delay(backoff.delayFor(attempt++))
                }
            }
        }

    private suspend fun FlowCollector<EventDto>.readSse(
        conversationId: String,
        cursor: Long,
        deliver: suspend FlowCollector<EventDto>.(EventDto) -> Unit,
    ) = coroutineScope {
        val url =
            client
                .authedRequest("/conversations/$conversationId/stream")
                .build()
                .url
                .newBuilder()
                .addQueryParameter("after", cursor.toString())
                .build()
        val request =
            client
                .authedRequest("/conversations/$conversationId/stream")
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", cursor.toString())
                .get()
                .build()
        val call = sseHttp.newCall(request)
        val items = Channel<Result<SseItem>>(Channel.UNLIMITED)
        val reader = launch(Dispatchers.IO) { pump(call, items) }
        try {
            var lastActivity = clock()
            while (true) {
                val remaining = IDLE_MS - (clock() - lastActivity)
                val received = withTimeoutOrNull(remaining.coerceAtLeast(0)) { items.receiveCatching() } ?: return@coroutineScope
                val result = received.getOrNull() ?: return@coroutineScope
                lastActivity = clock()
                val item = result.getOrThrow()
                if (item is SseItem.Message) parseEvent(item)?.let { deliver(it) }
            }
        } finally {
            call.cancel()
            reader.cancel()
        }
    }

    private fun pump(
        call: okhttp3.Call,
        items: Channel<Result<SseItem>>,
    ) {
        try {
            call.execute().use { response ->
                if (response.isSuccessful) {
                    readLines(response.body.source(), items)
                } else {
                    items.trySend(Result.failure(mapError(response.code, UNKNOWN, "", response.header("Retry-After"))))
                }
            }
        } catch (e: IOException) {
            items.trySend(Result.failure(ApiError.Network(e)))
        } finally {
            items.close()
        }
    }

    private fun readLines(
        source: okio.BufferedSource,
        items: Channel<Result<SseItem>>,
    ) {
        val parser = SseParser()
        while (true) {
            val line = source.readUtf8Line() ?: break
            parser.feed(line)?.let { items.trySend(Result.success(it)) }
        }
    }

    private fun parseEvent(message: SseItem.Message): EventDto? {
        val obj = runCatching { json.parseToJsonElement(message.data) as? JsonObject }.getOrNull() ?: return null
        val withCursor =
            if ("cursor" in obj || message.id == null) obj else JsonObject(obj + ("cursor" to JsonPrimitive(message.id)))
        val withType =
            if ("type" in withCursor ||
                message.event == null
            ) {
                withCursor
            } else {
                JsonObject(withCursor + ("type" to JsonPrimitive(message.event)))
            }
        return runCatching { eventFromJson(withType) }.getOrNull()
    }

    private companion object {
        const val LONG_POLL_WAIT_S = 25
        const val IDLE_MS = 45_000L
        const val UNKNOWN = "unknown"
        val json = Json { ignoreUnknownKeys = true }
    }
}
