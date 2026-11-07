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

/** `POST /pair` request: the 6-digit pairing code shown on the Devices page. */
@Serializable
data class PairIn(
    val code: String,
)

/** `POST /pair` answer: the device and its token, shown once. Never log it. */
@Serializable
data class PairedDto(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val plugin: String,
    val token: String,
) {
    override fun toString() = "PairedDto(deviceId=$deviceId, name=$name, plugin=$plugin, token=***)"
}

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
    /** The spec version whose text the board showed; required when a layout carries ops. */
    @SerialName("base_version") val baseVersion: Int? = null,
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
    @SerialName("latest_feedback_id") val latestFeedbackId: Int? = null,
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
    /** Line ids, provenance and op statuses; null for versions not written from notebook pages. */
    val layout: SpecLayoutDto? = null,
)

/** The `Geometry` schema: the notebook grid of a page in page-canvas px. */
@Serializable
data class GeometryDto(
    val cell: Int,
    @SerialName("row_pitch") val rowPitch: Int,
    val top: Int,
    val rows: Int,
    @SerialName("text_left") val textLeft: Int,
    @SerialName("margin_left") val marginLeft: Int,
)

/** The `Segment` schema: new handwriting of one row. [strokes] are indices into that page's strokes.json. */
@Serializable
data class SegmentDto(
    val id: String,
    val row: Int,
    val x0: Float,
    val x1: Float,
    val strokes: List<Int>,
)

/** The `VisibleRun` schema: a rendered spec line as the board showed it. */
@Serializable
data class VisibleRunDto(
    @SerialName("line_id") val lineId: String,
    val row: Int,
    val x0: Float,
)

/** The `OpTarget` schema: char offsets `[from, to)` in the markdown source line and the text there. */
@Serializable
data class OpTargetDto(
    @SerialName("line_id") val lineId: String,
    val from: Int,
    val to: Int,
    val quote: String,
)

/** The `OpAt` schema; [mode] is `inline` or `after_line`. */
@Serializable
data class OpAtDto(
    @SerialName("line_id") val lineId: String,
    val offset: Int,
    val mode: String,
)

/** The `Region` schema: an image op's region PNG part. */
@Serializable
data class RegionDto(
    val id: String,
    val part: String,
    @SerialName("after_line") val afterLine: String? = null,
)

/** The `EditOp` schema; [kind] is `replace`, `delete`, `insert` or `image`. */
@Serializable
data class EditOpDto(
    val id: String,
    val kind: String,
    val target: OpTargetDto? = null,
    val at: OpAtDto? = null,
    val strokes: List<Int> = emptyList(),
    val rect: List<Float>? = null,
    val region: RegionDto? = null,
)

/** The `PageLayout` schema: the `layout.<i>.json` part of a notebook page. */
@Serializable
data class PageLayoutDto(
    val version: Int = 1,
    val geometry: GeometryDto,
    val segments: List<SegmentDto> = emptyList(),
    val runs: List<VisibleRunDto> = emptyList(),
    val ops: List<EditOpDto> = emptyList(),
)

/** The `SpecLine` schema: one markdown source line (1-based) and its stable id. */
@Serializable
data class SpecLineDto(
    @SerialName("line_id") val lineId: String,
    @SerialName("source_line") val sourceLine: Int,
    /** `{"segments": [...]}`, `{"kept": "L12"}`, `{"op": "uuid"}` or null. */
    val origin: JsonObject? = null,
)

/** The `OpStatus` schema; [status] is `applied` or `rejected`. */
@Serializable
data class OpStatusDto(
    val id: String,
    val status: String,
    val note: String? = null,
)

/** The `SpecLayout` schema. */
@Serializable
data class SpecLayoutDto(
    val lines: List<SpecLineDto>,
    val ops: List<OpStatusDto> = emptyList(),
    @SerialName("base_version") val baseVersion: Int? = null,
    @SerialName("reverted_from") val revertedFrom: Int? = null,
)

/** The `RevertIn` schema: the latest spec version the app shows. */
@Serializable
data class RevertIn(
    @SerialName("base_version") val baseVersion: Int,
)

/** 201 body of `POST /conversations/{id}/spec/{version}/revert`. */
@Serializable
data class RevertResult(
    val version: Int,
    @SerialName("reverted_from") val revertedFrom: Int,
)

/** The `Feedback` schema: something the translator tells the user, kept apart from the document. */
@Serializable
data class FeedbackDto(
    val id: Int,
    /** `question`, `uncertain`, `illegible` or `note`; unknown kinds are shown as they are. */
    val kind: String,
    val text: String,
    val where: String? = null,
    @SerialName("spec_version") val specVersion: Int? = null,
    @SerialName("created_at") val createdAt: String,
)

/** Response of `GET /conversations/{id}/feedback`: items after the cursor, oldest first (at most 100). */
@Serializable
data class FeedbackPage(
    val items: List<FeedbackDto>,
    val cursor: Int,
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
