package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.rectOf
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas

/**
 * Crops an image region of a notebook page to an RGB PNG (spec 3.6): the page's text layer and visible ink inside the
 * region's rect on white, at canvas resolution (long edge capped at the connector limit), without the grid (never in
 * an export) and without region frames. Like [PngExporter] it shrinks by 0.85 until the PNG fits `max_png_bytes`;
 * null when it cannot.
 */
class RegionCropper(
    private val renderer: StrokeRenderer,
) {
    fun crop(
        page: Page,
        region: Stroke,
        textLayer: Bitmap?,
        canvas: Canvas,
        cfg: ConfigDto,
    ): ByteArray? {
        val r = rectOf(region)
        val left = floor(r.l).toInt().coerceIn(0, canvas.w)
        val top = floor(r.t).toInt().coerceIn(0, canvas.h)
        val right = ceil(r.r).toInt().coerceIn(0, canvas.w)
        val bottom = ceil(r.b).toInt().coerceIn(0, canvas.h)
        if (right <= left || bottom <= top) return null
        val ink = page.visibleStrokes.filter { it.tool != Tool.IMAGE_REGION }
        var scale = min(1f, cfg.maxLongEdgePx.toFloat() / max(right - left, bottom - top))
        var w = ((right - left) * scale).roundToInt().coerceAtLeast(1)
        var h = ((bottom - top) * scale).roundToInt().coerceAtLeast(1)
        while (true) {
            val png = encodeRgbPng(render(ink, textLayer, canvas, left, top, w, h, scale), w, h)
            if (png.size <= cfg.maxPngBytes) return png
            val nw = (w * SHRINK).roundToInt()
            val nh = (h * SHRINK).roundToInt()
            if (min(nw, nh) < MIN_EDGE) return null
            scale *= nw.toFloat() / w
            w = nw
            h = nh
        }
    }

    @Suppress("LongParameterList")
    private fun render(
        ink: List<Stroke>,
        textLayer: Bitmap?,
        canvas: Canvas,
        left: Int,
        top: Int,
        w: Int,
        h: Int,
        scale: Float,
    ): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        c.drawColor(Color.WHITE)
        textLayer?.let { layer ->
            val m =
                Matrix().apply {
                    // The layer is page sized (possibly at another resolution than the canvas).
                    postScale(canvas.w.toFloat() / layer.width, canvas.h.toFloat() / layer.height)
                    postTranslate(-left.toFloat(), -top.toFloat())
                    postScale(scale, scale)
                }
            c.drawBitmap(layer, m, null)
        }
        renderer.draw(c, ink, scale, -left * scale, -top * scale)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }

    private companion object {
        const val SHRINK = 0.85f

        /** A crop is not shrunk below this many px on its short edge. */
        const val MIN_EDGE = 16
    }
}
