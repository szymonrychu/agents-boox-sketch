package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertWithMessage
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.AtMode
import io.github.szymonrychu.booxsketch.model.notebook.EditOp
import io.github.szymonrychu.booxsketch.model.notebook.LayoutCodec
import io.github.szymonrychu.booxsketch.model.notebook.OpAt
import io.github.szymonrychu.booxsketch.model.notebook.OpKind
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.RegionRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Test

/** Checks the DTOs against the connector's `openapi.json` (refresh it with `mise run api:refresh <path>`). */
class ContractTest {
    private val spec: JsonObject =
        Json
            .parseToJsonElement(
                checkNotNull(javaClass.getResource("/openapi.json")) { "openapi.json missing" }.readText(),
            ).jsonObject

    private fun schema(name: String): JsonObject = spec["components"]!!.jsonObject["schemas"]!!.jsonObject[name]!!.jsonObject

    private fun response(
        path: String,
        method: String,
        status: String,
    ): JsonObject {
        val content =
            spec["paths"]!!
                .jsonObject[path]!!
                .jsonObject[method]!!
                .jsonObject["responses"]!!
                .jsonObject[status]!!
        return content.jsonObject["content"]!!
            .jsonObject.values
            .first()
            .jsonObject["schema"]!!
            .jsonObject
    }

    private fun items(schema: JsonObject): JsonObject = schema["items"]!!.jsonObject

    private inline fun <reified T> check(schema: JsonObject) = check(serializer<T>(), schema)

    private fun <T> check(
        serializer: KSerializer<T>,
        schema: JsonObject,
    ) {
        val name = serializer.descriptor.serialName.substringAfterLast('.')
        val fields = (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }
        val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val properties = schema["properties"]!!.jsonObject.keys
        assertWithMessage("$name has fields unknown to the schema").that(fields.filter { it !in properties }).isEmpty()
        assertWithMessage("$name misses required schema fields").that(required.filter { it !in fields }).isEmpty()
    }

    private val base = "/app/v1/sketch"

    @Test
    fun dtos_match_openapi() {
        check<ErrorResponse>(schema("ErrorResponse"))
        check<EventDto>(schema("Event"))
        check<SpecDto>(schema("Spec"))
        check<FeedbackDto>(schema("Feedback"))
        check<MessageIn>(schema("MessageIn"))
        check<HandoffDto>(schema("HandoffIn"))
        check<PresetTargetDto>(schema("PresetTarget"))
        check<ConfigDto>(response("$base/config", "get", "200"))
        check<ConversationDto>(items(response("$base/conversations", "get", "200")))
        check<ConversationCreated>(response("$base/conversations", "post", "201"))
        check<UploadResult>(response("$base/sketches", "post", "202"))
        check<MessageAck>(response("$base/conversations/{conversation_id}/messages", "post", "202"))
        check<HandoffResult>(response("$base/conversations/{conversation_id}/handoff", "post", "202"))
        val targets = response("$base/handoff/targets", "get", "200")
        check<HandoffTargetDto>(targets)
        check<SessionTargetDto>(items(targets["properties"]!!.jsonObject["sessions"]!!.jsonObject))
        check<FeedbackPage>(response("$base/conversations/{conversation_id}/feedback", "get", "200"))
        val events = response("$base/conversations/{conversation_id}/events", "get", "200")
        check<EventsResponse>(events)
    }

    @Test
    fun layout_and_spec_layout_match_openapi() {
        check<GeometryDto>(schema("Geometry"))
        check<SegmentDto>(schema("Segment"))
        check<VisibleRunDto>(schema("VisibleRun"))
        check<OpTargetDto>(schema("OpTarget"))
        check<OpAtDto>(schema("OpAt"))
        check<RegionDto>(schema("Region"))
        check<EditOpDto>(schema("EditOp"))
        check<PageLayoutDto>(schema("PageLayout"))
        check<SpecLineDto>(schema("SpecLine"))
        check<OpStatusDto>(schema("OpStatus"))
        check<SpecLayoutDto>(schema("SpecLayout"))
        check<RevertIn>(schema("RevertIn"))
        check<RevertResult>(response("$base/conversations/{conversation_id}/spec/{version}/revert", "post", "201"))
    }

    @Test
    fun layout_codec_output_decodes_strictly_as_page_layout() {
        val stroke = Stroke("a", Tool.BALLPOINT, 2f, listOf(Point(1f, 2f, 0.5f, 0L)))
        val op =
            EditOp(
                "11111111-1111-4111-8111-111111111111",
                OpKind.INSERT,
                at = OpAt("L1", 2, AtMode.INLINE),
                strokeIds = listOf("a"),
                rect = listOf(1f, 2f, 3f, 4f),
                region = RegionRef("22222222-2222-4222-8222-222222222222", "region.0.0.png", "L1"),
            )
        val page = Page(listOf(stroke), true)
        val bytes =
            LayoutCodec.encode(
                page,
                NotebookGeometry(1404, 1872),
                listOf(PendingSegment("c", "p0-r1-0", 1, 0f, 5f, listOf("a"))),
                listOf(op),
            )
        val strict = Json { ignoreUnknownKeys = false }
        val dto = strict.decodeFromString(PageLayoutDto.serializer(), String(bytes, Charsets.UTF_8))
        assertWithMessage("version").that(dto.version).isEqualTo(1)
        assertWithMessage("segment").that(dto.segments.single().strokes).containsExactly(0)
        assertWithMessage("op kind").that(dto.ops.single().kind).isEqualTo("insert")
        assertWithMessage("geometry").that(dto.geometry.marginLeft).isEqualTo(983)
    }

    @Test
    fun every_op_kind_is_a_documented_edit_op_kind() {
        val kinds =
            schema("EditOp")["properties"]!!
                .jsonObject["kind"]!!
                .jsonObject["enum"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertWithMessage("op kinds").that(kinds).containsAtLeastElementsIn(OpKind.entries.map { it.name.lowercase() })
    }

    @Test
    fun note_op_decodes_strictly_as_edit_op() {
        val page = Page(listOf(Stroke("a", Tool.BALLPOINT, 2f, listOf(Point(1f, 2f, 0.5f, 0L)))), true)
        val note = EditOp("33333333-3333-4333-8333-333333333333", OpKind.NOTE, strokeIds = listOf("a"), rect = listOf(1f, 2f, 3f, 4f))
        val bytes = LayoutCodec.encode(page, NotebookGeometry(1404, 1872), emptyList(), listOf(note))
        val dto = Json { ignoreUnknownKeys = false }.decodeFromString(PageLayoutDto.serializer(), String(bytes, Charsets.UTF_8))
        val op = dto.ops.single()
        assertWithMessage("kind").that(op.kind).isEqualTo("note")
        assertWithMessage("strokes").that(op.strokes).containsExactly(0)
        assertWithMessage("rect").that(op.rect).containsExactly(1f, 2f, 3f, 4f).inOrder()
        assertWithMessage("no target").that(op.target).isNull()
        assertWithMessage("no at").that(op.at).isNull()
    }

    @Test
    fun picture_region_matches_openapi() {
        check<PictureRegionDto>(schema("PictureRegion"))
    }

    @Test
    fun upload_meta_documents_page_regions() {
        val meta = schema("SketchUpload")["properties"]!!.jsonObject["meta"]!!.jsonObject["description"]!!.toString()
        assertWithMessage("meta documents regions").that(meta).contains("regions")
    }

    @Test
    fun upload_form_parts_match_schema() {
        val parts = schema("SketchUpload")["properties"]!!.jsonObject.keys
        assertWithMessage("meta and first page png are documented").that(parts).containsAtLeast("meta", "sketch.0.png")
        assertWithMessage("layout and region parts are documented")
            .that(parts)
            .containsAtLeast("layout.0.json", "region.0.0.png")
    }

    @Test
    fun every_documented_error_status_is_mapped() {
        val statuses =
            spec["paths"]!!
                .jsonObject
                .filterKeys { it.startsWith(base) }
                .values
                .flatMap { it.jsonObject.values }
                .flatMap { it.jsonObject["responses"]!!.jsonObject.keys }
                .map { it.toInt() }
                .filter { it >= 400 }
                .toSet()
        for (status in statuses) {
            val mapped = mapError(status, "code", "d", null)
            assertWithMessage("status $status").that(mapped).isNotNull()
        }
    }
}
