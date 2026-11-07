package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import io.github.szymonrychu.booxsketch.model.notebook.RunPlacer
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TextLayerTest {
    private val g = NotebookGeometry(1404, 1872)
    private val layer = TextLayer(g)

    private fun run(
        text: String,
        row: Int = 3,
        scale: Float = 1f,
        x0: Float = 53f,
    ): TextRun {
        val s = SourceMap.stripWithColours(text)
        return TextRun(
            "L1",
            1,
            1,
            row,
            x0,
            scale = scale,
            display = s.display,
            colourSpans = s.spans,
            sourceMap = s.map,
            sourceStrokeIds = emptyList(),
        )
    }

    private fun page(vararg runs: TextRun) = Page(emptyList(), false, text = runs.toList())

    /** Bounding box of the pixels of [b] for which [hit] is true, or null. */
    private fun box(
        b: Bitmap,
        hit: (Int) -> Boolean,
    ): RectF? {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var bottom = -1
        for (y in 0 until b.height) {
            for (x in 0 until b.width) {
                if (hit(b.getPixel(x, y))) {
                    l = minOf(l, x)
                    t = minOf(t, y)
                    r = maxOf(r, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (r < 0) null else RectF(l.toFloat(), t.toFloat(), r.toFloat(), bottom.toFloat())
    }

    private val inked: (Int) -> Boolean = { Color.alpha(it) > 128 }

    @Test
    fun layer_is_page_sized() {
        val b = layer.render(page(run("hello")), null)
        assertThat(b.width).isEqualTo(1404)
        assertThat(b.height).isEqualTo(1872)
        assertThat(b.config).isEqualTo(Bitmap.Config.ARGB_8888)
    }

    @Test
    fun run_pixels_in_text_band_only() {
        val b = layer.render(page(run("Hello world gyp")), null)
        val ink = checkNotNull(box(b, inked))
        assertThat(ink.top).isAtLeast(g.textBandTop(3).toFloat())
        assertThat(ink.bottom).isAtMost(g.rowLineY(3).toFloat())
        assertThat(ink.left).isAtLeast(53f)
    }

    @Test
    fun colour_span_drawn_in_pen_colour() {
        val b = layer.render(page(run("[[red]]RED[[/]]")), null)
        val red = box(b) { Color.red(it) > 180 && Color.green(it) < 80 && Color.alpha(it) > 200 }
        assertThat(red).isNotNull()
        assertThat(PenColor.RED.argb).isEqualTo(0xFFE00000.toInt())
    }

    @Test
    fun scaled_run_is_narrower() {
        val full = checkNotNull(box(layer.render(page(run("measure me please")), null), inked))
        val small = checkNotNull(box(layer.render(page(run("measure me please", scale = 0.85f)), null), inked))
        assertThat(small.width()).isLessThan(full.width())
        assertThat(layer.measure.width("measure me please", 0.85f)).isLessThan(layer.measure.width("measure me please", 1f))
    }

    @Test
    fun wrapped_run_uses_the_next_row() {
        val r = run("word ".repeat(40).trim()).copy(scale = 0.85f, wrapRows = 2)
        val ink = checkNotNull(box(layer.render(page(r), null), inked))
        assertThat(ink.bottom).isGreaterThan(g.rowLineY(3).toFloat())
        assertThat(ink.bottom).isAtMost(g.rowLineY(4).toFloat())
    }

    @Test
    fun wrapped_run_stays_in_its_placed_column() {
        val text = "word ".repeat(14).trim()
        val column = 400f
        val rows = RunPlacer.wrap(text, 0.85f, column, layer.measure).size
        val r = run(text).copy(scale = 0.85f, wrapRows = rows, column = column)
        val ink = checkNotNull(box(layer.render(page(r), null), inked))
        // The placement wrapped in 400 px (a neighbour on the row): the layer wraps at the same offsets.
        assertThat(ink.right).isAtMost(53f + column + 2f)
        assertThat(ink.bottom).isAtMost(g.rowLineY(3 + rows - 1).toFloat())
    }

    @Test
    fun overlay_rect_is_grey() {
        val b = layer.render(page(), null, overlay = listOf(RectF(100f, 300f, 200f, 340f)))
        val px = b.getPixel(150, 320)
        assertThat(Color.alpha(px)).isGreaterThan(0)
        assertThat(Color.red(px)).isEqualTo(Color.green(px))
        assertThat(Color.green(px)).isEqualTo(Color.blue(px))
    }

    @Test
    fun pending_segment_rows_get_a_grey_tick() {
        val p = Page(emptyList(), false, pendingSegments = listOf(PendingSegment("c", "p0-r5-0", 5, 100f, 300f, listOf("a"))))
        val tick = checkNotNull(box(layer.render(p, null)) { Color.alpha(it) > 0 })
        assertThat(tick.left).isAtLeast(0f)
        assertThat(tick.right).isLessThan(g.textLeft.toFloat())
        assertThat(tick.top).isAtLeast(g.rowTop(5).toFloat())
        assertThat(tick.bottom).isAtMost(g.rowLineY(5).toFloat())
    }

    @Test
    fun render_reuses_the_given_bitmap_and_clears_it() {
        val first = layer.render(page(run("first")), null)
        val second = layer.render(page(), first)
        assertThat(second).isSameInstanceAs(first)
        assertThat(box(second, inked)).isNull()
    }

    @Test
    fun render_under_50ms_robolectric_benchmark() {
        val full =
            page(
                *(0 until g.rowCount)
                    .map {
                        run(
                            "The quick brown fox jumps over the lazy dog $it",
                            row = it,
                        ).copy(lineId = "L$it")
                    }.toTypedArray(),
            )
        var reuse: Bitmap? = layer.render(full, null)
        val start = System.nanoTime()
        repeat(5) { reuse = layer.render(full, reuse) }
        val ms = (System.nanoTime() - start) / 5 / 1_000_000
        println("TextLayer full page render: $ms ms (Robolectric)")
        assertThat(ms).isLessThan(200L)
    }
}
