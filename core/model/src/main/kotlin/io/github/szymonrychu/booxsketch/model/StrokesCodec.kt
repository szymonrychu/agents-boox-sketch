package io.github.szymonrychu.booxsketch.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * strokes.json wire format, version 3. Point times are relative to the first point of the page.
 * A point is [x, y, pressure, t] and, when the pen reported tilt, [x, y, pressure, t, tiltX, tiltY]
 * (a missing axis is written as 0). Version 2 files (4 values per point) still decode.
 */
object StrokesCodec {
    private const val VERSION = 3
    private const val VERSION_NO_TILT = 2

    fun encode(
        canvas: Canvas,
        page: Page,
    ): ByteArray {
        val t0 =
            page.strokes
                .firstOrNull()
                ?.points
                ?.firstOrNull()
                ?.tMs ?: 0L
        val root =
            buildJsonObject {
                put("version", VERSION)
                put(
                    "canvas",
                    buildJsonObject {
                        put("w", canvas.w)
                        put("h", canvas.h)
                        put("dpi", canvas.dpi)
                    },
                )
                put(
                    "strokes",
                    buildJsonArray {
                        page.strokes.forEach { s ->
                            add(
                                buildJsonObject {
                                    put("tool", s.tool.wire)
                                    put("color", s.color.wire)
                                    put("width", s.width)
                                    put(
                                        "points",
                                        buildJsonArray {
                                            s.points.forEach { p ->
                                                val values =
                                                    mutableListOf(
                                                        JsonPrimitive(p.x),
                                                        JsonPrimitive(p.y),
                                                        JsonPrimitive(p.pressure),
                                                        JsonPrimitive(p.tMs - t0),
                                                    )
                                                if (p.tiltX != null || p.tiltY != null) {
                                                    values += JsonPrimitive(p.tiltX ?: 0f)
                                                    values += JsonPrimitive(p.tiltY ?: 0f)
                                                }
                                                add(JsonArray(values))
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        return Json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8)
    }

    /** Decodes strokes with generated ids ("s0", "s1", ...). Throws [IllegalArgumentException] on bad input. */
    fun decode(bytes: ByteArray): Pair<Canvas, List<Stroke>> {
        try {
            val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
            require(root.getValue("version").jsonPrimitive.int in VERSION_NO_TILT..VERSION) { "unsupported version" }
            val c = root.getValue("canvas").jsonObject
            val canvas = Canvas(c.getValue("w").jsonPrimitive.int, c.getValue("h").jsonPrimitive.int, c.getValue("dpi").jsonPrimitive.float)
            val strokes =
                root.getValue("strokes").jsonArray.mapIndexed { i, el ->
                    val o = el.jsonObject
                    val name = o.getValue("tool").jsonPrimitive.content
                    val tool = requireNotNull(Tool.entries.firstOrNull { it.wire == name }) { "unknown tool: $name" }
                    val colorName = o.getValue("color").jsonPrimitive.content
                    val color = requireNotNull(PenColor.entries.firstOrNull { it.wire == colorName }) { "unknown color: $colorName" }
                    val pts =
                        o.getValue("points").jsonArray.map { pe ->
                            val a = pe.jsonArray
                            require(a.size == 4 || a.size == 6) { "point needs 4 or 6 values" }
                            Point(
                                a[0].jsonPrimitive.float,
                                a[1].jsonPrimitive.float,
                                a[2].jsonPrimitive.float,
                                a[3].jsonPrimitive.long,
                                a.getOrNull(4)?.jsonPrimitive?.float,
                                a.getOrNull(5)?.jsonPrimitive?.float,
                            )
                        }
                    require(!tool.isShape || pts.size == 2) { "shape needs exactly 2 points" }
                    Stroke("s$i", tool, o.getValue("width").jsonPrimitive.float, pts, color)
                }
            return canvas to strokes
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("invalid strokes.json: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("invalid strokes.json: ${e.message}", e)
        }
    }
}
