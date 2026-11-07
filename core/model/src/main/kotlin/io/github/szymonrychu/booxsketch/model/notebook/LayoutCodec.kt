package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `layout.<i>.json` part of a notebook page (connector `PageLayout`, version 1): grid geometry, the new
 * handwriting segments, the visible text runs and the edit ops. Stroke ids become indices into the
 * `strokes.<i>.json` written by [io.github.szymonrychu.booxsketch.model.StrokesCodec] for the same page, which holds
 * the page's visible strokes (consumed ones are not in it).
 */
object LayoutCodec {
    private const val VERSION = 1

    /** Throws [IllegalArgumentException] when a segment or op names a stroke that is not visible on [page]. */
    fun encode(
        page: Page,
        geometry: NotebookGeometry,
        segments: List<PendingSegment>,
        ops: List<EditOp>,
    ): ByteArray {
        val index = page.visibleStrokes.withIndex().associate { (i, s) -> s.id to i }

        fun indices(ids: List<String>) =
            JsonArray(
                ids.map { id ->
                    JsonPrimitive(requireNotNull(index[id]) { "stroke $id is not a visible stroke of the page" })
                },
            )
        val root =
            buildJsonObject {
                put("version", VERSION)
                put("geometry", geometryJson(geometry))
                put(
                    "segments",
                    buildJsonArray {
                        segments.forEach { s ->
                            add(
                                buildJsonObject {
                                    put("id", s.segmentId)
                                    put("row", s.row)
                                    put("x0", s.x0)
                                    put("x1", s.x1)
                                    put("strokes", indices(s.strokeIds))
                                },
                            )
                        }
                    },
                )
                put(
                    "runs",
                    buildJsonArray {
                        page.text.forEach { r ->
                            add(
                                buildJsonObject {
                                    put("line_id", r.lineId)
                                    put("row", r.row)
                                    put("x0", r.x0)
                                },
                            )
                        }
                    },
                )
                put("ops", buildJsonArray { ops.forEach { add(opJson(it, ::indices)) } })
            }
        return Json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8)
    }

    private fun geometryJson(g: NotebookGeometry) =
        buildJsonObject {
            put("cell", g.cell)
            put("row_pitch", g.rowPitch)
            put("top", g.topMargin)
            put("rows", g.rowCount)
            put("text_left", g.textLeft)
            put("margin_left", g.marginX)
        }

    private fun opJson(
        op: EditOp,
        indices: (List<String>) -> JsonElement,
    ) = buildJsonObject {
        put("id", op.id)
        put("kind", op.kind.name.lowercase())
        op.target?.let { t ->
            put(
                "target",
                buildJsonObject {
                    put("line_id", t.lineId)
                    put("from", t.from)
                    put("to", t.to)
                    put("quote", t.quote)
                },
            )
        }
        op.at?.let { a ->
            put(
                "at",
                buildJsonObject {
                    put("line_id", a.lineId)
                    put("offset", a.offset)
                    put("mode", a.mode.name.lowercase())
                },
            )
        }
        put("strokes", indices(op.strokeIds))
        op.rect?.let { r -> put("rect", JsonArray(r.map { JsonPrimitive(it) })) }
        op.region?.let { r ->
            put(
                "region",
                buildJsonObject {
                    put("id", r.id)
                    put("part", r.part)
                    r.afterLine?.let { put("after_line", it) }
                },
            )
        }
    }
}
