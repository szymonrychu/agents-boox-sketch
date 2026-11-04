package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.shapeOutline
import io.github.szymonrychu.booxsketch.onyx.OnyxPenRenderer
import io.github.szymonrychu.booxsketch.onyx.PenRenderer

/**
 * How many times its width a stroke of [tool] may paint across: shapes 1x, marker 3x, and the
 * charcoal pens 8x, because their native texture stamps are scaled by the pen tilt (NeoPenConfig
 * tiltScale defaults to 3) on top of charcoal_v2's 1.2x width, so the grain reaches past the line.
 */
fun inkWidthFactor(tool: Tool): Float =
    when (tool) {
        Tool.RECTANGLE, Tool.ARROW -> 1f
        Tool.CHARCOAL, Tool.CHARCOAL_V2 -> CHARCOAL_INK_FACTOR
        else -> MARKER_INK_FACTOR
    }

private const val MARKER_INK_FACTOR = 3f
private const val CHARCOAL_INK_FACTOR = 8f
private const val AA_PAD = 2f

/**
 * Pixels [stroke] (page-canvas pixels, drawn at px * [scale] + ([dx], [dy])) can paint: its
 * outline points (an arrow's head included) padded by [inkWidthFactor] of its width plus
 * anti-aliasing. Null for a stroke without points.
 */
fun inkBounds(
    stroke: Stroke,
    scale: Float,
    dx: Float,
    dy: Float,
): RectF? {
    if (stroke.points.isEmpty()) return null
    val pad = stroke.width * scale * inkWidthFactor(stroke.tool) / 2f + AA_PAD
    val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    for (p in shapeOutline(stroke).flatten()) r.union(p.x * scale + dx, p.y * scale + dy)
    r.inset(-pad, -pad)
    return r
}

/** The device pens behind a bitmap cache, or null off-Onyx; what the board and document views draw with. */
fun devicePenRenderer(): PenRenderer? = OnyxPenRenderer.onDevice()?.let { CachedPenRenderer(it) }

/**
 * Runs the (slow, native) [pen] once per committed stroke and placement and keeps the result as a
 * bitmap of the stroke's [inkBounds], so a redraw (a shape drag frame, a dirty-rect sync, a Compose
 * draw pass) only blits it. A stroke the pen declines is remembered too and left to the caller.
 * Keys are the stroke instance (committed strokes are immutable and kept by reference) and the
 * placement; the cache holds at most [maxBytes] of bitmaps, least recently drawn evicted first.
 * Draws happen on one thread (the view's), as for any canvas.
 */
class CachedPenRenderer(
    private val pen: PenRenderer,
    maxBytes: Int = defaultBytes(),
) : PenRenderer {
    private sealed interface Entry {
        val bytes: Int
    }

    private object Declined : Entry {
        override val bytes = 1
    }

    private class Drawn(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
    ) : Entry {
        override val bytes = bitmap.allocationByteCount
    }

    private class Key(
        val stroke: Stroke,
        val scale: Float,
        val dx: Float,
        val dy: Float,
    ) {
        override fun equals(other: Any?) =
            other is Key && other.stroke === stroke && other.scale == scale && other.dx == dx && other.dy == dy

        override fun hashCode() =
            ((System.identityHashCode(stroke) * HASH + scale.hashCode()) * HASH + dx.hashCode()) * HASH + dy.hashCode()
    }

    private val cache =
        object : LruCache<Key, Entry>(maxBytes) {
            override fun sizeOf(
                key: Key,
                value: Entry,
            ) = value.bytes
        }

    /** Strokes the pen actually ran for; tests read it. */
    internal var rendered = 0
        private set

    override fun draw(
        canvas: Canvas,
        stroke: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        val key = Key(stroke, scale, dx, dy)
        val entry = cache.get(key) ?: render(stroke, scale, dx, dy).also { cache.put(key, it) }
        if (entry !is Drawn) return false
        canvas.drawBitmap(entry.bitmap, entry.left, entry.top, null)
        return true
    }

    private fun render(
        stroke: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Entry {
        val r = inkBounds(stroke, scale, dx, dy)?.let { Rect().apply { it.roundOut(this) } } ?: return Declined
        if (r.isEmpty) return Declined
        val bitmap = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        val offscreen = Canvas(bitmap).apply { translate(-r.left.toFloat(), -r.top.toFloat()) }
        rendered++
        if (!pen.draw(offscreen, stroke, scale, dx, dy)) {
            bitmap.recycle()
            return Declined
        }
        return Drawn(bitmap, r.left.toFloat(), r.top.toFloat())
    }

    private companion object {
        const val HASH = 31
        const val HEAP_SHARE = 8

        fun defaultBytes(): Int = (Runtime.getRuntime().maxMemory() / HEAP_SHARE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
