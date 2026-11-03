package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Color
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.RectF
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.planExport
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface ExportResult {
    data class Exported(
        val png: ByteArray,
        val w: Int,
        val h: Int,
    ) : ExportResult

    data object Empty : ExportResult

    data class TooBig(
        val reason: String,
    ) : ExportResult
}

/** Renders a page to a white-background 8-bit grayscale PNG within the connector limits. */
class PngExporter(
    private val renderer: StrokeRenderer,
) {
    fun export(
        page: Page,
        canvas: Canvas,
        cfg: ConfigDto,
    ): ExportResult {
        val plan = planExport(bounds(page.strokes), canvas, cfg.maxLongEdgePx, cfg.minEdgePx) ?: return ExportResult.Empty
        var w = plan.outW
        var h = plan.outH
        var scale = plan.scale
        while (true) {
            val png = encodeGray(render(page.strokes, w, h, scale, plan.cropLeft, plan.cropTop), w, h)
            if (png.size <= cfg.maxPngBytes) return ExportResult.Exported(png, w, h)
            val nw = (w * SHRINK).roundToInt()
            val nh = (h * SHRINK).roundToInt()
            if (min(nw, nh) < cfg.minEdgePx) {
                val reason = "png ${png.size} bytes exceeds cap ${cfg.maxPngBytes}"
                return ExportResult.TooBig("$reason, short edge ${min(w, h)} px cannot shrink below ${cfg.minEdgePx}")
            }
            scale *= nw.toFloat() / w
            w = nw
            h = nh
        }
    }

    private fun bounds(strokes: List<Stroke>): RectF? {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var any = false
        for (s in strokes) {
            // Pad by the widest rendered width (marker is 3x) so the crop never clips ink.
            val half = s.width * 3f / 2f
            for (p in s.points) {
                any = true
                l = min(l, p.x - half)
                t = min(t, p.y - half)
                r = max(r, p.x + half)
                b = max(b, p.y + half)
            }
        }
        return if (any) RectF(l, t, r, b) else null
    }

    private fun render(
        strokes: List<Stroke>,
        w: Int,
        h: Int,
        scale: Float,
        cropLeft: Int,
        cropTop: Int,
    ): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(Color.WHITE)
        renderer.draw(c, strokes, scale, -cropLeft * scale, -cropTop * scale)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }

    private fun encodeGray(
        argb: IntArray,
        w: Int,
        h: Int,
    ): ByteArray {
        val raw = ByteArray((w + 1) * h)
        for (y in 0 until h) {
            val row = y * (w + 1)
            for (x in 0 until w) {
                val p = argb[y * w + x]
                val g = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).roundToInt()
                raw[row + 1 + x] = g.toByte()
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        chunk(
            out,
            "IHDR",
            ByteBuffer
                .allocate(13)
                .putInt(w)
                .putInt(h)
                .put(8)
                .put(0)
                .put(0)
                .put(0)
                .put(0)
                .array(),
        )
        chunk(out, "IDAT", deflate(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(raw)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUF)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun chunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    private companion object {
        const val SHRINK = 0.85f
        const val BUF = 8192
    }
}
