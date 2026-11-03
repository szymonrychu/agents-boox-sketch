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
                Stroke("b", Tool.CHARCOAL_V2, 8f, listOf(p(100f, 100f, 1f, 1500))),
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
    fun decode_encode_roundtrip_without_tilt() {
        val bytes = StrokesCodec.encode(canvas, page)
        val (c, strokes) = StrokesCodec.decode(bytes)
        assertThat(c).isEqualTo(canvas)
        assertThat(strokes.map { it.tool }).containsExactly(Tool.FOUNTAIN, Tool.CHARCOAL_V2).inOrder()
        assertThat(strokes.flatMap { it.points }.all { it.tiltX == null && it.tiltY == null }).isTrue()
        assertThat(StrokesCodec.encode(c, Page(strokes, true))).isEqualTo(bytes)
    }

    @Test
    fun unknown_tool_rejected() {
        val json = """{"version":1,"canvas":{"w":1,"h":1,"dpi":1.0},"strokes":[{"tool":"crayon","width":1.0,"points":[[0,0,1,0]]}]}"""
        assertThrows(IllegalArgumentException::class.java) { StrokesCodec.decode(json.toByteArray()) }
    }
}
