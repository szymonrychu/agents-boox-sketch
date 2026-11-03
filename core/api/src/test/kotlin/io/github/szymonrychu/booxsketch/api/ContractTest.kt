package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        val events = response("$base/conversations/{conversation_id}/events", "get", "200")
        check<EventsResponse>(events)
    }

    @Test
    fun upload_form_parts_match_schema() {
        val parts = schema("SketchUpload")["properties"]!!.jsonObject.keys
        assertWithMessage("meta and first page png are documented").that(parts).containsAtLeast("meta", "sketch.0.png")
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
