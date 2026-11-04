package io.github.szymonrychu.booxsketch.send

import io.github.szymonrychu.booxsketch.api.HandoffDto
import io.github.szymonrychu.booxsketch.api.MessageIn
import io.github.szymonrychu.booxsketch.api.PageMetaDto
import io.github.szymonrychu.booxsketch.api.SketchMetaDto
import io.github.szymonrychu.booxsketch.model.Canvas
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** The page part of a sketch payload (`pages.json`): no conversation fields, those are resolved at send time. */
data class PagesFile(
    val clientId: String,
    val caption: String?,
    val pages: List<PageMetaDto>,
)

/** A hand-off payload (`handoff.json`): the request plus the spec version seen when it was queued. */
data class HandoffFile(
    val req: HandoffDto,
    val specVersion: Int?,
)

/**
 * Files of one outbox payload directory. Written once at enqueue time; `meta.json` is written
 * once when the item is first resolved, and every retry re-sends these exact bytes.
 */
object Payload {
    const val PAGES = "pages.json"
    const val META = "meta.json"
    const val MESSAGE = "message.json"
    const val HANDOFF = "handoff.json"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    private val pageList = ListSerializer(PageMetaDto.serializer())

    fun png(
        dir: File,
        n: Int,
    ) = File(dir, "sketch.$n.png")

    fun strokes(
        dir: File,
        n: Int,
    ) = File(dir, "strokes.$n.json")

    fun writePages(
        dir: File,
        clientId: String,
        caption: String?,
        indices: List<Int>,
        canvas: Canvas,
    ) {
        val obj =
            buildJsonObject {
                put("client_id", clientId)
                put("caption", caption?.let(::JsonPrimitive) ?: JsonNull)
                put("pages", json.encodeToJsonElement(pageList, indices.map { PageMetaDto(it, canvas) }))
            }
        File(dir, PAGES).writeText(obj.toString())
    }

    fun readPages(dir: File): PagesFile {
        val obj = json.parseToJsonElement(File(dir, PAGES).readText()).jsonObject
        return PagesFile(
            clientId = obj.getValue("client_id").jsonPrimitive.content,
            caption = obj["caption"]?.jsonPrimitive?.contentOrNull,
            pages = json.decodeFromJsonElement(pageList, obj.getValue("pages")),
        )
    }

    fun writeMeta(
        dir: File,
        meta: SketchMetaDto,
    ) = File(dir, META).writeText(json.encodeToString(SketchMetaDto.serializer(), meta))

    fun readMeta(dir: File): SketchMetaDto = json.decodeFromString(SketchMetaDto.serializer(), File(dir, META).readText())

    fun writeMessage(
        dir: File,
        clientId: String,
        text: String,
    ) = File(dir, MESSAGE).writeText(json.encodeToString(MessageIn.serializer(), MessageIn(clientId, text)))

    fun readMessage(dir: File): MessageIn = json.decodeFromString(MessageIn.serializer(), File(dir, MESSAGE).readText())

    fun writeHandoff(
        dir: File,
        req: HandoffDto,
        specVersion: Int?,
    ) {
        val obj =
            buildJsonObject {
                put("client_id", req.clientId)
                put("session", req.session)
                put("spec_version", specVersion?.let(::JsonPrimitive) ?: JsonNull)
            }
        File(dir, HANDOFF).writeText(obj.toString())
    }

    fun readHandoff(dir: File): HandoffFile {
        val obj: JsonObject = json.parseToJsonElement(File(dir, HANDOFF).readText()).jsonObject
        val version = obj["spec_version"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
        return HandoffFile(HandoffDto(obj.getValue("client_id").jsonPrimitive.content, obj.getValue("session")), version)
    }
}
