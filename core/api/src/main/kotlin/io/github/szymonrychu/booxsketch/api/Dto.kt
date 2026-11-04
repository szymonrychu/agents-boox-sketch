package io.github.szymonrychu.booxsketch.api

import io.github.szymonrychu.booxsketch.model.Canvas
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET /config`: connector limits and API version. */
@Serializable
data class ConfigDto(
    @SerialName("max_long_edge_px") val maxLongEdgePx: Int,
    @SerialName("max_png_bytes") val maxPngBytes: Int,
    @SerialName("max_strokes_bytes") val maxStrokesBytes: Int,
    @SerialName("max_pages") val maxPages: Int,
    @SerialName("max_upload_bytes") val maxUploadBytes: Int,
    @SerialName("min_edge_px") val minEdgePx: Int,
    @SerialName("api_version") val apiVersion: Int,
)

/** One page entry of the upload `meta`: its index and canvas. */
@Serializable
data class PageMetaDto(
    val page: Int,
    val canvas: Canvas,
)

/** The `meta` form field of a sketch upload. [conversation] is `current` or `new`. */
@Serializable
data class SketchMetaDto(
    @SerialName("client_id") val clientId: String,
    val conversation: String = "current",
    @SerialName("conversation_id") val conversationId: String? = null,
    val caption: String? = null,
    val pages: List<PageMetaDto>,
    val annotates: AnnotatesDto? = null,
)

/**
 * One uploaded page of a notes upload: [page] is a page number of `meta.pages`, [docPage] the rendered document page,
 * [lines] its source line range.
 */
@Serializable
data class AnnotatedPageDto(
    val page: Int,
    @SerialName("doc_page") val docPage: Int,
    val lines: List<Int>,
)

/** `meta.annotates`: the upload is handwritten notes over spec [specVersion]. */
@Serializable
data class AnnotatesDto(
    @SerialName("spec_version") val specVersion: Int,
    val pages: List<AnnotatedPageDto>,
)

/** 202 (or 200 replay) body of `POST /sketches`. */
@Serializable
data class UploadResult(
    @SerialName("sketch_id") val sketchId: String,
    @SerialName("conversation_id") val conversationId: String,
    val status: String,
)

/** The `MessageIn` schema. */
@Serializable
data class MessageIn(
    @SerialName("client_id") val clientId: String,
    val text: String,
)

/** 202 body of `POST /conversations/{id}/messages`. */
@Serializable
data class MessageAck(
    @SerialName("message_id") val messageId: String,
)

/** One row of `GET /conversations`. */
@Serializable
data class ConversationDto(
    val id: String,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("latest_spec_version") val latestSpecVersion: Int? = null,
)

/** 201 body of `POST /conversations`. */
@Serializable
data class ConversationCreated(
    val id: String,
)

/** Response of `GET /conversations/{id}/events`; each event keeps its flattened payload. */
@Serializable
data class EventsResponse(
    val events: List<JsonObject>,
    val cursor: Long,
)

/** The `Event` schema. The connector flattens the event payload next to these fields; it lands in [payload]. */
@Serializable
data class EventDto(
    val cursor: Long,
    val type: String,
    @SerialName("created_at") val createdAt: String,
    @Transient val payload: JsonObject = JsonObject(emptyMap()),
)

/** The `Spec` schema. */
@Serializable
data class SpecDto(
    val version: Int,
    val markdown: String,
    val created: String,
    @SerialName("sketch_ids") val sketchIds: List<String>,
)

/** A live session a spec can be handed to. */
@Serializable
data class SessionTargetDto(
    val id: String,
    val slug: String,
    @SerialName("preset_name") val presetName: String? = null,
)

/** Response of `GET /handoff/targets`. */
@Serializable
data class HandoffTargetDto(
    val sessions: List<SessionTargetDto>,
    val presets: List<String>,
)

/** The `PresetTarget` schema. */
@Serializable
data class PresetTargetDto(
    val preset: String,
)

/** The `HandoffIn` schema: [session] is a session slug string or a `{"preset": name}` object. */
@Serializable
data class HandoffDto(
    @SerialName("client_id") val clientId: String,
    val session: JsonElement,
) {
    companion object {
        fun toSession(
            clientId: String,
            slug: String,
        ) = HandoffDto(clientId, JsonPrimitive(slug))

        fun toPreset(
            clientId: String,
            preset: String,
        ) = HandoffDto(clientId, buildJsonObject { put("preset", preset) })
    }
}

/** 202 body of `POST /conversations/{id}/handoff`. */
@Serializable
data class HandoffResult(
    @SerialName("handoff_id") val handoffId: String,
    @SerialName("session_slug") val sessionSlug: String,
)
