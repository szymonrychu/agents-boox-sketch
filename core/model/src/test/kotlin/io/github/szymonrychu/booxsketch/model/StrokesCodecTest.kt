package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class StrokesCodecTest {
    private val canvas = Canvas(1200, 1600, 300f)

    private fun p(
        x: Float,
        y: Float,
        pr: Float,
        t: Long,
        tilt: Float? = null,
    ) = Point(x, y, pr, t, tilt, tilt)

    private val page =
        Page(
            listOf(
                Stroke("a", Tool.FOUNTAIN, 4f, listOf(p(10f, 20f, 0.5f, 1000, 0.1f), p(30f, 40f, 0.75f, 1016))),
                Stroke("b", Tool.RECTANGLE, 4f, listOf(p(100f, 100f, 1f, 1500), p(400f, 300f, 1f, 1900)), PenColor.RED),
                Stroke("c", Tool.ARROW, 4f, listOf(p(400f, 200f, 1f, 2200), p(700f, 200f, 1f, 2500)), PenColor.BLUE),
            ),
            true,
        )

    @Test
    fun encode_matches_schema_example() {
        val expected =
            javaClass.getResource("/strokes-example.json")!!.readText().filterNot { it.isWhitespace() }
        val actual = String(StrokesCodec.encode(canvas, page), Charsets.UTF_8).filterNot { it.isWhitespace() }
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun decode_encode_roundtrip_keeps_tilt() {
        val bytes = StrokesCodec.encode(canvas, page)
        val (c, strokes) = StrokesCodec.decode(bytes)
        assertThat(c).isEqualTo(canvas)
        assertThat(strokes.map { it.tool }).containsExactly(Tool.FOUNTAIN, Tool.RECTANGLE, Tool.ARROW).inOrder()
        assertThat(strokes[0].points[0].tiltX).isEqualTo(0.1f)
        assertThat(strokes[0].points[0].tiltY).isEqualTo(0.1f)
        assertThat(strokes[0].points[1].tiltX).isNull()
        assertThat(strokes[0].points[1].tiltY).isNull()
        assertThat(StrokesCodec.encode(c, Page(strokes, true))).isEqualTo(bytes)
    }

    @Test
    fun tilt_is_appended_only_when_reported_and_a_missing_axis_is_zero() {
        val one = Page(listOf(Stroke("a", Tool.BALLPOINT, 4f, listOf(Point(1f, 2f, 0.5f, 0, 0.25f, null), Point(3f, 4f, 0.5f, 5)))), true)
        val text = String(StrokesCodec.encode(canvas, one))
        assertThat(text).contains("[1.0,2.0,0.5,0,0.25,0.0],[3.0,4.0,0.5,5]")
        val pts = StrokesCodec.decode(text.toByteArray()).second[0].points
        assertThat(pts[0].tiltY).isEqualTo(0f)
        assertThat(pts[1].tiltX).isNull()
    }

    @Test
    fun version_2_without_tilt_still_decodes() {
        val bytes = doc(2, """{"tool":"fountain","color":"black","width":1.0,"points":$pts2}""")
        val pts = StrokesCodec.decode(bytes).second[0].points
        assertThat(pts).hasSize(2)
        assertThat(pts[0].tiltX).isNull()
    }

    @Test
    fun point_with_five_values_rejected() {
        val bytes = doc(stroke = """{"tool":"fountain","color":"black","width":1.0,"points":[[0,0,1,0,0.5]]}""")
        assertThrows(IllegalArgumentException::class.java) { StrokesCodec.decode(bytes) }
    }

    @Test
    fun unknown_tool_rejected() {
        val bytes = doc(stroke = """{"tool":"crayon","color":"black","width":1.0,"points":[[0,0,1,0]]}""")
        assertThrows(IllegalArgumentException::class.java) { StrokesCodec.decode(bytes) }
    }

    private fun doc(
        version: Int = 3,
        stroke: String,
    ) = """{"version":$version,"canvas":{"w":1,"h":1,"dpi":1.0},"strokes":[$stroke]}""".toByteArray()

    private val pts2 = """[[0,0,1,0],[1,1,1,5]]"""

    @Test
    fun version_1_rejected() {
        val bytes = doc(1, """{"tool":"fountain","color":"black","width":1.0,"points":$pts2}""")
        assertThrows(IllegalArgumentException::class.java) { StrokesCodec.decode(bytes) }
    }

    @Test
    fun missing_color_rejected() {
        val bytes = doc(stroke = """{"tool":"fountain","width":1.0,"points":$pts2}""")
        assertThrows(IllegalArgumentException::class.java) { StrokesCodec.decode(bytes) }
    }

    @Test
    fun unknown_color_rejected() {
        val bytes = doc(stroke = """{"tool":"fountain","color":"pink","width":1.0,"points":$pts2}""")
        assertThrows(IllegalArgumentException::class.java) { StrokesCodec.decode(bytes) }
    }

    @Test
    fun shape_with_three_points_rejected() {
        val three = """[[0,0,1,0],[1,1,1,5],[2,2,1,9]]"""
        val bytes = doc(stroke = """{"tool":"rectangle","color":"red","width":1.0,"points":$three}""")
        assertThrows(IllegalArgumentException::class.java) { StrokesCodec.decode(bytes) }
    }

    @Test
    fun colour_and_shapes_roundtrip() {
        val (_, strokes) = StrokesCodec.decode(StrokesCodec.encode(canvas, page))
        assertThat(strokes.map { it.color }).containsExactly(PenColor.BLACK, PenColor.RED, PenColor.BLUE).inOrder()
        assertThat(strokes[1].points).hasSize(2)
        assertThat(strokes[2].tool).isEqualTo(Tool.ARROW)
    }
}
