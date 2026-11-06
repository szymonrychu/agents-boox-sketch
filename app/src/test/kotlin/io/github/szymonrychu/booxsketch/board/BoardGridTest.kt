package io.github.szymonrychu.booxsketch.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.NotebookGeometry
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/** Records the grid primitives: lines as (x1, y1, x2, y2), dots as (x, y), and the paints used. */
private class GridRecorder : Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) {
    val lines = mutableListOf<List<Float>>()
    val dots = mutableListOf<List<Float>>()
    var linePaint: Paint? = null
    var dotPaint: Paint? = null

    override fun drawLines(
        pts: FloatArray,
        offset: Int,
        count: Int,
        paint: Paint,
    ) {
        linePaint = paint
        for (i in offset until offset + count step 4) lines += listOf(pts[i], pts[i + 1], pts[i + 2], pts[i + 3])
    }

    override fun drawPoints(
        pts: FloatArray,
        offset: Int,
        count: Int,
        paint: Paint,
    ) {
        dotPaint = paint
        for (i in offset until offset + count step 2) dots += listOf(pts[i], pts[i + 1])
    }

    val horizontal get() = lines.filter { it[1] == it[3] }
    val vertical get() = lines.filter { it[0] == it[2] }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardGridTest {
    private val page = PageCanvas(1404, 1872, 300f)
    private val geo = NotebookGeometry(1404, 1872)
    private val portrait = BoardGeometry(1860, 2480, page)
    private val landscape = BoardGeometry(2480, 1860, page)

    private fun draw(
        g: BoardGeometry,
        within: RectF? = null,
    ) = GridRecorder().also { BoardGrid().draw(it, g, page, within) }

    /** The integer view y a 2 px line at page y [y] is centred on: it covers the pixel rows just above and below. */
    private fun lineY(
        g: BoardGeometry,
        y: Int,
    ) = (g.dy + y * g.scale).roundToInt().toFloat()

    @Test
    fun row_lines_run_over_the_text_column_only_at_every_row_pitch() {
        val c = draw(portrait)
        assertThat(c.horizontal).hasSize(16)
        val ys = c.horizontal.map { it[1] }
        assertThat(ys).containsExactlyElementsIn((0 until 16).map { lineY(portrait, geo.rowLineY(it)) }).inOrder()
        c.horizontal.forEach {
            assertThat(it[0]).isWithin(1f).of(portrait.dx + geo.textLeft * portrait.scale)
            assertThat(it[2]).isWithin(1f).of(portrait.dx + geo.marginX * portrait.scale)
        }
    }

    @Test
    fun margin_has_one_vertical_line_and_no_row_lines() {
        val c = draw(portrait)
        assertThat(c.vertical).hasSize(1)
        val v = c.vertical.single()
        assertThat(v[0]).isWithin(1f).of(portrait.dx + 983 * portrait.scale)
        assertThat(v[1]).isWithin(1f).of(portrait.dy)
        assertThat(v[3]).isWithin(1f).of(portrait.dy + 1872 * portrait.scale)
        c.horizontal.forEach { assertThat(it[2]).isAtMost(v[0] + 1f) }
    }

    @Test
    fun dots_sit_at_cell_intersections_and_skip_row_lines_in_the_text_column() {
        val c = draw(portrait)
        val expected = mutableListOf<List<Float>>()
        for (j in geo.dotRowsIn(0f, 1872f)) {
            for (i in geo.dotColumnsIn(0f, 1404f)) {
                if (!geo.dotCovered(i, j)) expected += listOf(portrait.dx + i * 53 * portrait.scale, portrait.dy + j * 53 * portrait.scale)
            }
        }
        assertThat(c.dots).hasSize(expected.size)
        c.dots.zip(expected).forEach { (got, want) ->
            assertThat(got[0]).isWithin(1f).of(want[0])
            assertThat(got[1]).isWithin(1f).of(want[1])
        }
        // Dots are sparse: far fewer than a full set of lines would be pixels.
        assertThat(c.dots.size).isLessThan(1000)
    }

    @Test
    fun margin_shows_dots_where_the_text_column_has_lines() {
        val c = draw(portrait)
        val onFirstLine = c.dots.filter { abs(it[1] - (portrait.dy + 212 * portrait.scale)) < 1f }
        assertThat(onFirstLine).isNotEmpty()
        onFirstLine.forEach { assertThat(it[0]).isAtLeast(portrait.dx + 983 * portrait.scale - 1f) }
    }

    @Test
    fun colours_and_widths() {
        val c = draw(portrait)
        assertThat(c.linePaint!!.color).isEqualTo(0xFF999999.toInt())
        assertThat(c.linePaint!!.strokeWidth).isEqualTo(2f)
        assertThat(c.linePaint!!.isAntiAlias).isFalse()
        assertThat(c.dotPaint!!.color).isEqualTo(0xFF777777.toInt())
        assertThat(c.dotPaint!!.isAntiAlias).isFalse()
        assertThat(c.dotPaint!!.strokeCap).isEqualTo(Paint.Cap.SQUARE)
        // 2 page px, scaled with the page, never below 2 view px.
        assertThat(c.dotPaint!!.strokeWidth).isWithin(1f).of(2f * portrait.scale)
    }

    @Test
    fun landscape_keeps_the_grid_locked_to_page_coordinates() {
        val c = draw(landscape)
        assertThat(landscape.scale).isLessThan(1f)
        assertThat(c.horizontal).hasSize(16)
        // A stroke point on the first row line is drawn on the first grid line.
        val onLine = landscape.toCanvas(strokeAtViewY(landscape.dy + 212 * landscape.scale))
        assertThat(onLine.points[0].y).isWithin(0.01f).of(212f)
        assertThat(c.horizontal[0][1]).isWithin(1f).of(landscape.dy + 212 * landscape.scale)
        c.horizontal.forEach {
            assertThat(it[0]).isWithin(1f).of(landscape.dx + 53 * landscape.scale)
            assertThat(it[2]).isWithin(1f).of(landscape.dx + 983 * landscape.scale)
        }
        // The side areas beside the page stay blank.
        val pageLeft = landscape.dx
        val pageRight = landscape.dx + 1404 * landscape.scale
        c.dots.forEach {
            assertThat(it[0]).isIn(
                com.google.common.collect.Range
                    .closed(pageLeft, pageRight),
            )
        }
        assertThat(c.vertical.single()[0]).isWithin(1f).of(landscape.dx + 983 * landscape.scale)
    }

    @Test
    fun grid_lines_sit_on_the_same_pixels_as_ink_at_the_same_page_y_in_both_orientations() {
        for (g in listOf(portrait, landscape)) {
            val c = draw(g)
            for (row in 0 until 16) {
                val stroke =
                    Stroke(
                        "s",
                        Tool.BALLPOINT,
                        2f,
                        listOf(Point(100f, geo.rowLineY(row).toFloat(), 1f, 0), Point(300f, geo.rowLineY(row).toFloat(), 1f, 5)),
                    )
                val inkY = g.inkBounds(stroke)!!.centerY()
                assertThat(c.horizontal[row][1]).isWithin(1f).of(inkY)
            }
        }
    }

    @Test
    fun only_what_is_inside_the_clip_is_drawn() {
        val clip = RectF(portrait.dx + 100f, portrait.dy + 300f, portrait.dx + 500f, portrait.dy + 480f)
        val c = draw(portrait, clip)
        c.horizontal.forEach {
            assertThat(it[1]).isAtLeast(clip.top - 1f)
            assertThat(it[1]).isAtMost(clip.bottom + 1f)
            assertThat(it[0]).isAtLeast(clip.left - 1f)
            assertThat(it[2]).isAtMost(clip.right + 1f)
        }
        c.dots.forEach {
            assertThat(it[0]).isIn(
                com.google.common.collect.Range
                    .closed(clip.left - 2f, clip.right + 2f),
            )
            assertThat(it[1]).isIn(
                com.google.common.collect.Range
                    .closed(clip.top - 2f, clip.bottom + 2f),
            )
        }
        assertThat(c.horizontal).isNotEmpty()
        assertThat(c.dots).isNotEmpty()
        assertThat(c.vertical).isEmpty()
    }

    @Test
    fun a_clip_outside_the_page_draws_nothing() {
        val c = draw(landscape, RectF(0f, 0f, landscape.dx - 5f, 100f))
        assertThat(c.lines).isEmpty()
        assertThat(c.dots).isEmpty()
    }

    @Test
    fun a_clip_around_the_margin_line_draws_it_clipped() {
        val x = portrait.dx + 983 * portrait.scale
        val c = draw(portrait, RectF(x - 10f, 500f, x + 10f, 700f))
        val v = c.vertical.single()
        assertThat(v[1]).isAtLeast(500f)
        assertThat(v[3]).isAtMost(700f)
    }

    @Test
    fun grid_colours_are_neutral_greys_on_the_16_level_eink_palette_and_dark_enough() {
        for (c in listOf(BoardGrid.LINE_COLOR, BoardGrid.DOT_COLOR)) {
            val v = c and 0xFF
            assertThat(v % 0x11).isEqualTo(0)
            assertThat((c shr 8) and 0xFF).isEqualTo(v)
            assertThat((c shr 16) and 0xFF).isEqualTo(v)
        }
        assertThat(BoardGrid.LINE_COLOR and 0xFF).isAtMost(0x99)
        assertThat(BoardGrid.DOT_COLOR and 0xFF).isAtMost(0x77)
    }

    @Test
    fun row_and_margin_lines_cover_two_whole_pixel_rows() {
        for (g in listOf(portrait, landscape)) {
            val c = draw(g)
            assertThat(c.linePaint!!.strokeWidth).isEqualTo(2f)
            c.horizontal.forEach { assertThat(it[1]).isEqualTo(floor(it[1])) }
            c.vertical.forEach { assertThat(it[0]).isEqualTo(floor(it[0])) }
        }
    }

    @Test
    fun a_dirty_rect_edge_on_a_line_pixel_row_still_draws_that_line() {
        for (g in listOf(portrait, landscape)) {
            for (k in 0 until geo.rowCount) {
                val y = lineY(g, geo.rowLineY(k))
                // The line covers view rows y - 1 and y; a dirty rect starting or ending on either must redraw it.
                for (clip in listOf(
                    RectF(g.dx + 100f, y - 1f, g.dx + 400f, y + 30f),
                    RectF(g.dx + 100f, y, g.dx + 400f, y + 30f),
                    RectF(g.dx + 100f, y - 30f, g.dx + 400f, y),
                    RectF(g.dx + 100f, y - 30f, g.dx + 400f, y + 1f),
                )) {
                    val c = draw(g, clip)
                    assertThat(c.horizontal.map { it[1] }).contains(y)
                }
            }
        }
    }

    private fun strokeAtViewY(y: Float) = Stroke("s", Tool.BALLPOINT, 2f, listOf(Point(landscape.dx + 10f, y, 1f, 0)))

    @Test
    fun surface_paints_the_grid_before_the_ink_inside_the_dirty_clip_only() {
        val calls = mutableListOf<String>()
        val renderer =
            object : io.github.szymonrychu.booxsketch.render.StrokeRenderer {
                override fun draw(
                    canvas: Canvas,
                    strokes: List<io.github.szymonrychu.booxsketch.model.Stroke>,
                    scale: Float,
                    dx: Float,
                    dy: Float,
                ) {
                    calls += "ink"
                }
            }
        val view = BoardSurfaceView(ApplicationProvider.getApplicationContext(), renderer)
        var frames = 0
        val rec =
            object : Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) {
                override fun drawLines(
                    pts: FloatArray,
                    offset: Int,
                    count: Int,
                    paint: Paint,
                ) {
                    calls += "grid"
                }

                override fun drawPoints(
                    pts: FloatArray,
                    offset: Int,
                    count: Int,
                    paint: Paint,
                ) {
                    calls += "grid"
                }
            }
        view.canvasSource = {
            frames++
            rec
        }
        view.surfaceCreated(view.holder)
        calls.clear()
        view.show(
            PageCanvas(1404, 1872, 300f),
            listOf(
                io.github.szymonrychu.booxsketch.model
                    .Stroke("s", io.github.szymonrychu.booxsketch.model.Tool.BALLPOINT, 2f, emptyList()),
            ),
            0,
        )
        assertThat(calls.indexOf("grid")).isAtLeast(0)
        assertThat(calls.indexOf("grid")).isLessThan(calls.indexOf("ink"))
    }
}
