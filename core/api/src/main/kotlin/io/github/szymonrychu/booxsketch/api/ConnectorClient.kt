package io.github.szymonrychu.booxsketch.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP client of the connector's sketch plugin (`/app/v1/sketch`). */
@Suppress("TooManyFunctions") // one method per connector endpoint, per the task interface
class ConnectorClient(
    origin: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient,
) {
    private val base = "${origin.trimEnd('/')}/app/v1/sketch"

    /** A request builder for [path] under the sketch API with the bearer header, for callers that open their own streams. */
    fun authedRequest(path: String): Request.Builder {
        val builder = Request.Builder().url("$base$path")
        tokenProvider()?.let { builder.header("Authorization", BEARER_PREFIX + it) }
        return builder
    }

    suspend fun config(): ConfigDto = call(authedRequest("/config").get().build())

    suspend fun uploadSketch(
        meta: SketchMetaDto,
        pngs: List<ByteArray>,
        strokes: List<ByteArray?>,
    ): UploadResult {
        val body =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("meta", null, json.encodeToString(meta).toRequestBody(JSON_TYPE))
        pngs.forEachIndexed { i, png ->
            body.addFormDataPart("sketch.$i.png", "sketch.$i.png", png.toRequestBody(PNG_TYPE))
        }
        strokes.forEachIndexed { i, data ->
            if (data != null) {
                body.addFormDataPart("strokes.$i.json", "strokes.$i.json", data.toRequestBody(JSON_TYPE))
            }
        }
        return call(authedRequest("/sketches").post(body.build()).build())
    }

    suspend fun postMessage(
        conversationId: String,
        clientId: String,
        text: String,
    ): String {
        val body = json.encodeToString(MessageIn(clientId, text)).toRequestBody(JSON_TYPE)
        val ack: MessageAck = call(authedRequest("/conversations/$conversationId/messages").post(body).build())
        return ack.messageId
    }

    suspend fun conversations(): List<ConversationDto> = call(authedRequest("/conversations").get().build())

    suspend fun createConversation(): String {
        val created: ConversationCreated =
            call(authedRequest("/conversations").post(ByteArray(0).toRequestBody(null)).build())
        return created.id
    }

    suspend fun deleteConversation(id: String) {
        execute(authedRequest("/conversations/$id").delete().build()) { }
    }

    suspend fun events(
        id: String,
        after: Long,
        waitS: Int,
    ): List<EventDto> {
        val url = "$base/conversations/$id/events".toHttpUrl().newBuilder()
        url.addQueryParameter("after", after.toString())
        url.addQueryParameter("wait", waitS.toString())
        val pollClient = http.newBuilder().readTimeout(waitS + POLL_TIMEOUT_MARGIN_S, TimeUnit.SECONDS).build()
        val response: EventsResponse =
            call(authedRequest("/conversations/$id/events").url(url.build()).get().build(), pollClient)
        return response.events.map(::eventFromJson)
    }

    suspend fun spec(
        id: String,
        version: Int?,
    ): SpecDto {
        val suffix = if (version == null) "/spec" else "/spec/$version"
        return call(authedRequest("/conversations/$id$suffix").get().build())
    }

    suspend fun specMarkdown(id: String): String = execute(authedRequest("/conversations/$id/spec.md").get().build()) { it.body.string() }

    /** The stored bytes of a sketch file; [ApiError.Purged] after retention, [ApiError.NotFound] when unknown. */
    suspend fun attachment(id: String): ByteArray = execute(authedRequest("/attachments/$id").get().build()) { it.body.bytes() }

    suspend fun handoffTargets(): HandoffTargetDto = call(authedRequest("/handoff/targets").get().build())

    suspend fun handoff(
        id: String,
        req: HandoffDto,
    ) {
        val body = json.encodeToString(req).toRequestBody(JSON_TYPE)
        execute(authedRequest("/conversations/$id/handoff").post(body).build()) { }
    }

    private suspend inline fun <reified T> call(
        request: Request,
        client: OkHttpClient = http,
    ): T =
        execute(request, client) { response ->
            try {
                json.decodeFromString<T>(response.body.string())
            } catch (e: SerializationException) {
                throw IOException("unreadable response: ${e.message}", e)
            }
        }

    private suspend fun <T> execute(
        request: Request,
        client: OkHttpClient = http,
        parse: (Response) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parse(response)
                    } else {
                        throw errorOf(response)
                    }
                }
            } catch (e: IOException) {
                throw ApiError.Network(e)
            }
        }

    private fun errorOf(response: Response): ApiError {
        val parsed =
            runCatching { json.decodeFromString<ErrorResponse>(response.body.string()) }.getOrNull()
        return mapError(
            status = response.code,
            code = parsed?.error ?: UNKNOWN_CODE,
            detail = parsed?.detail.orEmpty(),
            retryAfter = response.header("Retry-After"),
        )
    }

    private companion object {
        const val POLL_TIMEOUT_MARGIN_S = 10L
        const val BEARER_PREFIX = "Bear" + "er "
        val JSON_TYPE = "application/json".toMediaType()
        val PNG_TYPE = "image/png".toMediaType()
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
    }
}

private val EVENT_FIELDS = setOf("cursor", "type", "created_at")

/** Builds an [EventDto] from the flat event object (`cursor`, `type`, `created_at` plus payload fields). */
internal fun eventFromJson(raw: JsonObject): EventDto =
    EventDto(
        cursor =
            raw
                .getValue("cursor")
                .jsonPrimitive.content
                .toLong(),
        type = raw.getValue("type").jsonPrimitive.content,
        createdAt = raw.getValue("created_at").jsonPrimitive.content,
        payload = JsonObject(raw - EVENT_FIELDS),
    )
