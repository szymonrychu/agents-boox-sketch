package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas

/**
 * Exports a whole page: the background bitmap (a frozen document page, or a notebook page's text layer) with the
 * page's visible strokes over it, as an RGB PNG whose long
 * edge is capped at the connector limit and which shrinks until it fits the size cap, like [PngExporter]. Unlike it,
 * nothing is cropped: the notes keep their place on the page text.
 */
class PageRasterExporter(
    private val renderer: StrokeRenderer,
) {
    fun export(
        background: Bitmap,
        page: Page,
        canvas: Canvas,
        cfg: ConfigDto,
    ): ExportResult {
        if (page.visibleStrokes.isEmpty()) return ExportResult.Empty
        val long = max(canvas.w, canvas.h)
        var scale = min(1f, cfg.maxLongEdgePx.toFloat() / long)
        var w = (canvas.w * scale).roundToInt().coerceAtLeast(1)
        var h = (canvas.h * scale).roundToInt().coerceAtLeast(1)
        while (true) {
            val png = encodeRgbPng(render(background, page, w, h, scale), w, h)
            if (png.size <= cfg.maxPngBytes) return ExportResult.Exported(png, w, h)
            val nw = (w * SHRINK).roundToInt()
            val nh = (h * SHRINK).roundToInt()
            if (min(nw, nh) < cfg.minEdgePx) {
                return ExportResult.TooBig(
                    "png ${png.size} bytes exceeds cap ${cfg.maxPngBytes}, short edge ${min(w, h)} px cannot shrink below ${cfg.minEdgePx}",
                )
            }
            scale *= nw.toFloat() / w
            w = nw
            h = nh
        }
    }

    private fun render(
        background: Bitmap,
        page: Page,
        w: Int,
        h: Int,
        scale: Float,
    ): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        c.drawColor(Color.WHITE)
        val m = Matrix().apply { postScale(w.toFloat() / background.width, h.toFloat() / background.height) }
        c.drawBitmap(background, m, null)
        renderer.draw(c, page.visibleStrokes, scale, 0f, 0f)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }

    private companion object {
        const val SHRINK = 0.85f
    }
}
