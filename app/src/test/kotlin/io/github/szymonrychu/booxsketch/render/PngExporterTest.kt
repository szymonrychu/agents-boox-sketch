package io.github.szymonrychu.booxsketch.render

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PngExporterTest {
    private val canvas = Canvas(1000, 1400, 300f)
    private val exporter = PngExporter(CanvasStrokeRenderer())

    private fun cfg(
        maxLong: Int = 1568,
        maxBytes: Int = 1_000_000,
        minEdge: Int = 200,
    ) = ConfigDto(maxLong, maxBytes, 1_000_000, 10, 9_000_000, minEdge, 1)

    private fun stroke(
        tool: Tool = Tool.BALLPOINT,
        width: Float = 6f,
        pts: List<Pair<Float, Float>>,
        color: PenColor = PenColor.BLACK,
    ) = Stroke("s", tool, width, pts.mapIndexed { i, (x, y) -> Point(x, y, 0.8f, i * 10L) }, color)

    private fun page(vararg s: Stroke) = Page(s.toList(), true)

    private fun exported(r: ExportResult): ExportResult.Exported {
        assertThat(r).isInstanceOf(ExportResult.Exported::class.java)
        return r as ExportResult.Exported
    }

    /** Decodes 8-bit RGB PNG into (w, h, pixels) where each pixel is 0xRRGGBB. */
    private fun decode(png: ByteArray): Triple<Int, Int, IntArray> {
        val bb = ByteBuffer.wrap(png)
        bb.position(16)
        val w = bb.int
        val h = bb.int
        val idat = java.io.ByteArrayOutputStream()
        var pos = 8
        while (pos < png.size) {
            val len = ByteBuffer.wrap(png, pos, 4).int
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            if (type == "IDAT") idat.write(png, pos + 8, len)
            pos += 12 + len
        }
        val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
        val px = IntArray(w * h)
        val stride = 1 + 3 * w
        for (y in 0 until h) {
            assertThat(raw[y * stride].toInt()).isEqualTo(0)
            for (x in 0 until w) {
                val o = y * stride + 1 + 3 * x
                px[y * w + x] = ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
            }
        }
        return Triple(w, h, px)
    }

    private val diag = listOf(100f to 100f, 300f to 300f, 500f to 200f)

    @Test
    fun png_is_rgb_8bit() {
        val png = exported(exporter.export(page(stroke(pts = diag)), canvas, cfg())).png
        assertThat(png.copyOfRange(0, 8)).isEqualTo(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        assertThat(png[24].toInt()).isEqualTo(8)
        assertThat(png[25].toInt()).isEqualTo(2)
    }

    @Test
    fun dimensions_follow_export_plan() {
        val r = exported(exporter.export(page(stroke(pts = listOf(0f to 0f, 3000f to 100f))), Canvas(3200, 1400, 300f), cfg()))
        assertThat(maxOf(r.w, r.h)).isEqualTo(1568)
        val (w, h, _) = decode(r.png)
        assertThat(w).isEqualTo(r.w)
        assertThat(h).isEqualTo(r.h)
    }

    @Test
    fun background_white_corners() {
        val r = exported(exporter.export(page(stroke(pts = diag)), canvas, cfg()))
        val (w, h, px) = decode(r.png)
        listOf(0, w - 1, (h - 1) * w, h * w - 1).forEach { assertThat(px[it]).isEqualTo(0xFFFFFF) }
    }

    @Test
    fun stroke_pixels_dark() {
        val r = exported(exporter.export(page(stroke(pts = diag)), canvas, cfg()))
        val (_, _, px) = decode(r.png)
        assertThat(px.count { ((it shr 16) and 0xFF) < 80 && ((it shr 8) and 0xFF) < 80 && (it and 0xFF) < 80 }).isGreaterThan(100)
    }

    @Test
    fun red_stroke_exports_red_pixels() {
        val r = exported(exporter.export(page(stroke(pts = diag, color = PenColor.RED)), canvas, cfg()))
        val (_, _, px) = decode(r.png)
        val red = px.count { ((it shr 16) and 0xFF) > 180 && ((it shr 8) and 0xFF) < 60 && (it and 0xFF) < 60 }
        assertThat(red).isGreaterThan(100)
    }

    @Test
    fun arrow_head_not_clipped_by_crop() {
        val arrow = stroke(Tool.ARROW, 2f, listOf(100f to 300f, 500f to 300f), PenColor.BLUE)
        val r = exported(exporter.export(page(arrow), canvas, cfg()))
        val (w, h, px) = decode(r.png)
        val ys = (0 until h).filter { y -> (0 until w).any { x -> px[y * w + x] != 0xFFFFFF } }
        val xs = (0 until w).filter { x -> (0 until h).any { y -> px[y * w + x] != 0xFFFFFF } }
        // wings run off the shaft line, so the ink spans several rows and stays clear of every edge
        assertThat(ys.size).isGreaterThan(8)
        assertThat(ys.first()).isGreaterThan(0)
        assertThat(ys.last()).isLessThan(h - 1)
        assertThat(xs.first()).isGreaterThan(0)
        assertThat(xs.last()).isLessThan(w - 1)
    }

    @Test
    fun dense_colour_page_stays_under_1mb() {
        val strokes =
            PenColor.entries.flatMapIndexed { ci, color ->
                (0 until 12).map { k ->
                    val y0 = 40f + (ci * 12 + k) * 18f
                    stroke(
                        Tool.BALLPOINT,
                        6f,
                        (0 until 40).map { i -> (20f + i * 24f) to (y0 + if (i % 2 == 0) 0f else 60f) },
                        color,
                    )
                }
            }
        val r = exported(exporter.export(page(*strokes.toTypedArray()), canvas, cfg()))
        assertThat(r.png.size).isLessThan(1_000_000)
    }

    @Test
    fun shrinks_until_under_byte_cap() {
        val big = stroke(Tool.BALLPOINT, 8f, (0 until 6).map { (100f + it * 150f) to (100f + (it % 2) * 1000f) })
        val p = page(big)
        val full = exported(exporter.export(p, canvas, cfg()))
        val cap = full.png.size * 3 / 4
        val small = exported(exporter.export(p, canvas, cfg(maxBytes = cap)))
        assertThat(small.png.size).isAtMost(cap)
        assertThat(small.w).isLessThan(full.w)
    }

    @Test
    fun empty_page_returns_empty() {
        assertThat(exporter.export(Page(emptyList(), false), canvas, cfg())).isEqualTo(ExportResult.Empty)
    }

    @Test
    fun unshrinkable_page_returns_too_big_with_reason() {
        val r = exporter.export(page(stroke(pts = diag)), canvas, cfg(maxBytes = 10))
        assertThat(r).isInstanceOf(ExportResult.TooBig::class.java)
        val reason = (r as ExportResult.TooBig).reason
        assertThat(reason).contains("10")
        assertThat(reason).contains("short edge")
    }
}
