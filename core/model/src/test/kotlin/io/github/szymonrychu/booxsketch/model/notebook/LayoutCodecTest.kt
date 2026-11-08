package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.StrokesCodec
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class LayoutCodecTest {
    private val canvas = Canvas(1404, 1872, 300f)
    private val geometry = NotebookGeometry(1404, 1872)

    private fun stroke(id: String) = Stroke(id, Tool.BALLPOINT, 2f, listOf(Point(1f, 2f, 0.5f, 0L)))

    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun indices(o: JsonObject) = o["strokes"]!!.jsonArray.map { it.jsonPrimitive.int }

    private fun run(
        lineId: String,
        row: Int,
    ) = SourceMap.stripWithColours("x").let {
        TextRun(lineId, 1, 1, row, 53f, display = it.display, colourSpans = it.spans, sourceMap = it.map, sourceStrokeIds = emptyList())
    }

    @Test
    fun geometry_segments_runs_and_version() {
        val page =
            Page(
                listOf(stroke("a"), stroke("b")),
                true,
                text = listOf(run("L1", 2), run("L2", 3)),
            )
        val seg = PendingSegment("c", "p0-r4-0", 4, 60f, 900f, listOf("a", "b"))
        val o = parse(LayoutCodec.encode(page, geometry, listOf(seg), emptyList()))
        assertThat(o["version"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(o.keys).doesNotContain("kind")
        val g = o["geometry"]!!.jsonObject
        assertThat(g["cell"]!!.jsonPrimitive.int).isEqualTo(53)
        assertThat(g["row_pitch"]!!.jsonPrimitive.int).isEqualTo(106)
        assertThat(g["top"]!!.jsonPrimitive.int).isEqualTo(106)
        assertThat(g["rows"]!!.jsonPrimitive.int).isEqualTo(geometry.rowCount)
        assertThat(g["text_left"]!!.jsonPrimitive.int).isEqualTo(53)
        assertThat(g["margin_left"]!!.jsonPrimitive.int).isEqualTo(983)
        val s = o["segments"]!!.jsonArray[0].jsonObject
        assertThat(s["id"]!!.jsonPrimitive.content).isEqualTo("p0-r4-0")
        assertThat(s["row"]!!.jsonPrimitive.int).isEqualTo(4)
        assertThat(indices(s)).containsExactly(0, 1).inOrder()
        val runs = o["runs"]!!.jsonArray.map { it.jsonObject }
        assertThat(runs.map { it["line_id"]!!.jsonPrimitive.content }).containsExactly("L1", "L2").inOrder()
        assertThat(runs[0]["row"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(runs[0]["x0"]!!.jsonPrimitive.content).isEqualTo("53.0")
    }

    @Test
    fun stroke_ids_map_to_strokes_json_indices() {
        val page = Page(listOf(stroke("a"), stroke("b"), stroke("c")), true)
        val op =
            EditOp(
                id = "11111111-1111-4111-8111-111111111111",
                kind = OpKind.REPLACE,
                target = OpTarget("L1", 4, 9, "milk"),
                strokeIds = listOf("c", "a"),
                rect = listOf(1f, 2f, 3f, 4f),
            )
        val o = parse(LayoutCodec.encode(page, geometry, emptyList(), listOf(op)))
        val wire = o["ops"]!!.jsonArray[0].jsonObject
        assertThat(wire["kind"]!!.jsonPrimitive.content).isEqualTo("replace")
        assertThat(indices(wire)).containsExactly(2, 0).inOrder()
        val t = wire["target"]!!.jsonObject
        assertThat(t["line_id"]!!.jsonPrimitive.content).isEqualTo("L1")
        assertThat(t["from"]!!.jsonPrimitive.int).isEqualTo(4)
        assertThat(t["quote"]!!.jsonPrimitive.content).isEqualTo("milk")
        assertThat(wire.keys).doesNotContain("at")
        assertThat(wire["rect"]!!.jsonArray).hasSize(4)
    }

    @Test
    fun consumed_strokes_not_in_indices() {
        val page = Page(listOf(stroke("a"), stroke("b"), stroke("c")), true, consumed = setOf("a"))
        val seg = PendingSegment("c", "p0-r0-0", 0, 0f, 1f, listOf("b", "c"))
        val o = parse(LayoutCodec.encode(page, geometry, listOf(seg), emptyList()))
        assertThat(indices(o["segments"]!!.jsonArray[0].jsonObject)).containsExactly(0, 1).inOrder()
        val strokesJson = parse(StrokesCodec.encode(canvas, page))
        assertThat(strokesJson["strokes"]!!.jsonArray).hasSize(2)
    }

    @Test
    fun insert_and_image_ops_carry_at_and_region() {
        val page = Page(listOf(stroke("a")), true)
        val insert =
            EditOp(
                "i1",
                OpKind.INSERT,
                at = OpAt("L3", 5, AtMode.AFTER_LINE),
                strokeIds = listOf("a"),
            )
        val image =
            EditOp(
                "i2",
                OpKind.IMAGE,
                strokeIds = emptyList(),
                rect = listOf(0f, 0f, 10f, 10f),
                region = RegionRef("r1", "region.0.0.png", "L2"),
            )
        val o = parse(LayoutCodec.encode(page, geometry, emptyList(), listOf(insert, image)))
        val ops = o["ops"]!!.jsonArray.map { it.jsonObject }
        val at = ops[0]["at"]!!.jsonObject
        assertThat(at["mode"]!!.jsonPrimitive.content).isEqualTo("after_line")
        assertThat(at["offset"]!!.jsonPrimitive.int).isEqualTo(5)
        val region = ops[1]["region"]!!.jsonObject
        assertThat(region["part"]!!.jsonPrimitive.content).isEqualTo("region.0.0.png")
        assertThat(region["after_line"]!!.jsonPrimitive.content).isEqualTo("L2")
        assertThat(ops[1]["kind"]!!.jsonPrimitive.content).isEqualTo("image")
    }

    @Test
    fun note_op_carries_strokes_and_rect_only() {
        val page = Page(listOf(stroke("a"), stroke("b"), stroke("c")), true)
        val note = EditOp("n1", OpKind.NOTE, strokeIds = listOf("b", "c"), rect = listOf(990f, 300f, 200f, 90f))
        val wire = parse(LayoutCodec.encode(page, geometry, emptyList(), listOf(note)))["ops"]!!.jsonArray.single().jsonObject
        assertThat(wire["kind"]!!.jsonPrimitive.content).isEqualTo("note")
        assertThat(indices(wire)).containsExactly(1, 2).inOrder()
        assertThat(wire["rect"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("990.0", "300.0", "200.0", "90.0").inOrder()
        assertThat(wire.keys).containsExactly("id", "kind", "strokes", "rect")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknown_stroke_id_is_refused() {
        val page = Page(listOf(stroke("a")), true, consumed = setOf("a"))
        LayoutCodec.encode(page, geometry, listOf(PendingSegment("c", "p0-r0-0", 0, 0f, 1f, listOf("a"))), emptyList())
    }
}
