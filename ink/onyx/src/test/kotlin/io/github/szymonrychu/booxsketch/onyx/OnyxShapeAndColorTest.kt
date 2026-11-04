package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.data.TouchPointList
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tracks the native raw drawing state and the last brush colour. */
private class ColorRawDrawing : RawDrawing {
    var on = false
    var color: Int? = null
    val colors = mutableListOf<Int>()

    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun open() {
        on = false
    }

    override fun setEnabled(enabled: Boolean) {
        on = enabled
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) {
        this.color = color
        colors += color
    }

    override fun close() {
        on = false
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxShapeAndColorTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val view = View(ctx)
    private val raw = ColorRawDrawing()
    private var cb: RawInputCallback? = null
    private val draws = ArrayDeque<Runnable>()
    private val input =
        OnyxStrokeInput(
            ctx,
            newId = { "s1" },
            rawDrawing = { _, c ->
                cb = c
                raw
            },
            maxPressure = { 4096f },
            afterNextDraw = { _, r -> draws.addLast(r) },
            fullRefresh = {},
        )

    private fun started() {
        input.start(view, Rect(0, 0, 100, 100), emptyList())
        draws.removeFirst().run()
    }

    @Test
    fun selected_colour_reaches_the_raw_brush_and_is_reapplied_after_every_enable() {
        started()
        input.setColor(PenColor.RED)
        assertThat(raw.color).isEqualTo(PenColor.RED.argb)
        input.pause()
        raw.colors.clear()
        input.resume()
        assertThat(raw.colors).containsExactly(PenColor.RED.argb)
    }

    @Test
    fun colour_set_before_start_is_applied_by_the_first_brush() {
        input.setColor(PenColor.BLUE)
        input.start(view, Rect(0, 0, 100, 100), emptyList())
        assertThat(raw.color).isEqualTo(PenColor.BLUE.argb)
    }

    @Test
    fun committed_raw_stroke_carries_the_selected_colour() =
        runBlocking {
            started()
            input.setColor(PenColor.GREEN)
            val list = TouchPointList().apply { add(TouchPoint(1f, 1f, 2048f, 1f, 0, 0, 0L)) }
            cb!!.onRawDrawingTouchPointListReceived(list)
            val ev = withTimeout(1_000) { input.events.first() } as StrokeEvent.Committed
            assertThat(ev.stroke.color).isEqualTo(PenColor.GREEN)
        }

    @Test
    fun shape_tool_turns_raw_drawing_off_and_a_pen_tool_turns_it_back_on() {
        started()
        assertThat(raw.on).isTrue()
        input.setTool(Tool.RECTANGLE, 4f)
        assertThat(raw.on).isFalse()
        assertThat(input.rawActive).isFalse()
        input.setTool(Tool.ARROW, 4f)
        assertThat(raw.on).isFalse()
        input.setTool(Tool.BALLPOINT, 4f)
        assertThat(raw.on).isTrue()
        assertThat(input.rawActive).isTrue()
    }

    @Test
    fun nothing_re_enables_raw_drawing_while_a_shape_tool_is_selected() {
        started()
        input.setTool(Tool.RECTANGLE, 4f)
        input.holdUi()
        input.releaseUi()
        assertThat(raw.on).isFalse()
        input.pause()
        input.resume()
        assertThat(raw.on).isFalse()
        var drawn = false
        input.refresh { drawn = true }
        assertThat(drawn).isTrue()
        assertThat(raw.on).isFalse()
        input.start(view, Rect(0, 0, 100, 100), listOf(Rect(0, 90, 100, 100)))
        assertThat(raw.on).isFalse()
    }

    @Test
    fun a_new_session_with_a_shape_tool_stays_off_after_its_first_draw() {
        input.setTool(Tool.ARROW, 4f)
        input.start(view, Rect(0, 0, 100, 100), emptyList())
        draws.removeFirst().run()
        assertThat(raw.on).isFalse()
        input.setTool(Tool.FOUNTAIN, 4f)
        assertThat(raw.on).isTrue()
    }

    @Test
    fun holds_taken_during_a_shape_tool_are_released_and_do_not_keep_the_pen_off() {
        started()
        input.setTool(Tool.RECTANGLE, 4f)
        input.holdUi()
        input.setTool(Tool.BALLPOINT, 4f)
        assertThat(raw.on).isFalse()
        input.releaseUi()
        assertThat(raw.on).isTrue()
    }

    @Test
    fun switching_tool_while_paused_keeps_raw_drawing_off() {
        started()
        input.setTool(Tool.RECTANGLE, 4f)
        input.pause()
        input.setTool(Tool.BALLPOINT, 4f)
        assertThat(raw.on).isFalse()
        input.resume()
        assertThat(raw.on).isTrue()
    }

    @Test
    fun entering_a_shape_tool_ends_a_cut_stroke() {
        started()
        val p = TouchPoint(1f, 1f, 2048f, 1f, 0, 0, 0L)
        cb!!.onBeginRawDrawing(false, p)
        input.setTool(Tool.RECTANGLE, 4f)
        assertThat(input.strokeInProgress).isFalse()
    }
}
