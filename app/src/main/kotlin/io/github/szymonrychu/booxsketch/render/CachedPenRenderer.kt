package io.github.szymonrychu.booxsketch.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.shapeOutline
import io.github.szymonrychu.booxsketch.onyx.OnyxPenRenderer
import io.github.szymonrychu.booxsketch.onyx.PenRenderer

/**
 * How many times its width a stroke of [tool] may paint across: shapes 1x, ballpoint and marker 3x,
 * fountain and brush 6x, and the charcoal pens 8x. The native fountain and brush pens size each
 * point from pressure and velocity (and the V2 fountain adds 3 px to the width), so their outline
 * reaches past the nominal width; the charcoal texture stamps are scaled by the pen tilt
 * (NeoPenConfig tiltScale defaults to 3) on top of charcoal_v2's 1.2x width.
 */
fun inkWidthFactor(tool: Tool): Float =
    when (tool) {
        Tool.RECTANGLE, Tool.ARROW -> 1f
        Tool.FOUNTAIN, Tool.BRUSH -> SDK_PEN_INK_FACTOR
        Tool.CHARCOAL, Tool.CHARCOAL_V2 -> CHARCOAL_INK_FACTOR
        Tool.BALLPOINT, Tool.MARKER -> MARKER_INK_FACTOR
    }

private const val MARKER_INK_FACTOR = 3f
private const val SDK_PEN_INK_FACTOR = 6f
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

/**
 * The device pens behind a bitmap cache, or null off-Onyx; what the board and document views draw
 * with. [screenOrigin] is where the drawn canvas's (0, 0) sits on the screen (the charcoal pens'
 * screen matrix).
 */
fun devicePenRenderer(screenOrigin: () -> PointF = ::PointF): PenRenderer? =
    OnyxPenRenderer.onDevice(screenOrigin)?.let { CachedPenRenderer(it) }

/**
 * Runs the (slow, native) [pen] once per committed stroke and placement and keeps the result as a
 * bitmap of the stroke's [inkBounds] clipped to the canvas, so a redraw (a shape drag frame, a
 * dirty-rect sync, a Compose draw pass) only blits it. A stroke the pen does not handle
 * ([PenRenderer.handles]) is declined before any bitmap exists; one the pen runs on and draws
 * nothing for is remembered in a small capped set. Both are left to the caller, as is any stroke
 * whose bitmap cannot be allocated. Keys are the stroke instance (committed strokes are immutable
 * and kept by reference), the placement and the canvas size; the cache holds at most [maxBytes] of
 * bitmaps, least recently drawn evicted first. Draws happen on one thread (the view's), as for
 * any canvas.
 */
class CachedPenRenderer internal constructor(
    private val pen: PenRenderer,
    maxBytes: Int,
    private val createBitmap: (Int, Int) -> Bitmap,
) : PenRenderer {
    constructor(pen: PenRenderer, maxBytes: Int = defaultBytes()) : this(pen, maxBytes, ::argbBitmap)

    private class Drawn(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
    )

    private class Key(
        val stroke: Stroke,
        val scale: Float,
        val dx: Float,
        val dy: Float,
        val width: Int,
        val height: Int,
    ) {
        override fun equals(other: Any?) =
            other is Key &&
                other.stroke === stroke &&
                other.scale == scale &&
                other.dx == dx &&
                other.dy == dy &&
                other.width == width &&
                other.height == height

        override fun hashCode() =
            (((System.identityHashCode(stroke) * HASH + scale.hashCode()) * HASH + dx.hashCode()) * HASH + dy.hashCode()) * HASH +
                width * HASH + height
    }

    private val cache =
        object : LruCache<Key, Drawn>(maxBytes) {
            override fun sizeOf(
                key: Key,
                value: Drawn,
            ) = value.bitmap.allocationByteCount
        }

    private val declined = LruCache<Key, Boolean>(MAX_DECLINED)

    /** Strokes the pen actually ran for; tests read it. */
    internal var rendered = 0
        private set

    override fun handles(stroke: Stroke) = pen.handles(stroke)

    override fun draw(
        canvas: Canvas,
        stroke: Stroke,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        if (!pen.handles(stroke)) return false
        val key = Key(stroke, scale, dx, dy, canvas.width, canvas.height)
        if (declined.get(key) != null) return false
        val entry = cache.get(key) ?: render(key) ?: return false
        canvas.drawBitmap(entry.bitmap, entry.left, entry.top, null)
        return true
    }

    private fun render(key: Key): Drawn? {
        val r = inkBounds(key.stroke, key.scale, key.dx, key.dy)?.let { Rect().apply { it.roundOut(this) } }
        if (r == null || r.isEmpty) return decline(key)
        // Wholly outside the canvas: nothing to blit, and nothing worth remembering.
        if (!r.intersect(0, 0, key.width, key.height)) return null
        val bitmap = allocate(r.width(), r.height()) ?: return null
        return runPen(key, bitmap, r) ?: decline(key)
    }

    private fun runPen(
        key: Key,
        bitmap: Bitmap,
        r: Rect,
    ): Drawn? {
        rendered++
        val offscreen = Canvas(bitmap).apply { translate(-r.left.toFloat(), -r.top.toFloat()) }
        if (!pen.draw(offscreen, key.stroke, key.scale, key.dx, key.dy)) {
            bitmap.recycle()
            return null
        }
        return Drawn(bitmap, r.left.toFloat(), r.top.toFloat()).also { cache.put(key, it) }
    }

    /** Null when the heap cannot hold it; the cache is dropped so the next draw has room. */
    private fun allocate(
        w: Int,
        h: Int,
    ): Bitmap? =
        try {
            createBitmap(w, h)
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
            null
        }

    private fun decline(key: Key): Drawn? {
        declined.put(key, true)
        return null
    }

    private companion object {
        const val HASH = 31
        const val HEAP_SHARE = 8
        const val MAX_DECLINED = 256

        fun argbBitmap(
            w: Int,
            h: Int,
        ): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        fun defaultBytes(): Int = (Runtime.getRuntime().maxMemory() / HEAP_SHARE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
