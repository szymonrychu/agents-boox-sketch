package io.github.szymonrychu.booxsketch.onyx

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keeps the state a raw drawing helper ends up in. Like the SDK (`RawInputReader.setExcludeRect`,
 * onyxsdk-pen 1.5.8) an empty exclude list is ignored, so the previous native exclude stays.
 */
private class StateRawDrawing : RawDrawing {
    var on = false
    var limit: Rect? = null
    var exclude: List<Rect> = emptyList()

    override fun setLimitRect(
        limit: Rect,
        exclude: List<Rect>,
    ) {
        this.limit = Rect(limit)
        if (exclude.isNotEmpty()) this.exclude = exclude.map { Rect(it) }
    }

    override fun open() = Unit

    override fun setEnabled(enabled: Boolean) {
        on = enabled
    }

    override fun setBrush(
        style: Int,
        width: Float,
        color: Int,
    ) = Unit

    override fun close() {
        on = false
    }
}

/**
 * Live ink needs raw drawing enabled over the whole board after setup and after every chrome
 * transition, and [OnyxStrokeInput.rawActive] must say exactly when a stylus down on the board
 * can get a raw callback (the fallback watch relies on it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxLiveInkTest {
    private val view = View(ApplicationProvider.getApplicationContext())
    private val raw = StateRawDrawing()
    private val draws = ArrayDeque<Runnable>()
    private val input =
        OnyxStrokeInput(
            ApplicationProvider.getApplicationContext(),
            rawDrawing = { _, _ -> raw },
            maxPressure = { 4096f },
            afterNextDraw = { _, r -> draws.addLast(r) },
            fullRefresh = { },
        )
    private val board = Rect(0, 0, 100, 100)
    private val tabBar = Rect(0, 0, 100, 10)

    private fun drawFrames() {
        while (draws.isNotEmpty()) draws.removeFirst().run()
    }

    private fun assertLive() {
        assertThat(input.rawActive).isTrue()
        assertThat(raw.on).isTrue()
        assertThat(raw.limit).isEqualTo(board)
    }

    @Test
    fun raw_drawing_is_live_over_the_board_after_setup() {
        input.start(view, board, listOf(tabBar))
        assertThat(input.rawActive).isFalse()
        drawFrames()
        assertLive()
    }

    @Test
    fun raw_drawing_is_live_after_the_first_frame_hold_of_the_toolbar_refresher() {
        input.holdUi()
        input.start(view, board, listOf(tabBar))
        assertThat(input.rawActive).isFalse()
        input.releaseUi()
        assertLive()
    }

    @Test
    fun raw_drawing_is_off_only_during_a_toolbar_refresh_hold() {
        input.start(view, board, emptyList())
        drawFrames()
        input.holdUi()
        assertThat(input.rawActive).isFalse()
        assertThat(raw.on).isFalse()
        input.releaseUi()
        assertLive()
    }

    @Test
    fun raw_drawing_is_live_after_the_chrome_shows_and_hides() {
        input.start(view, board, emptyList())
        drawFrames()
        input.start(view, board, listOf(tabBar))
        assertLive()
        assertThat(raw.exclude).containsExactly(tabBar)
        input.start(view, board, emptyList())
        assertLive()
    }

    @Test
    fun raw_drawing_is_live_after_a_board_redraw() {
        input.start(view, board, emptyList())
        drawFrames()
        var redrawn = false
        input.refresh { redrawn = true }
        assertThat(redrawn).isTrue()
        assertLive()
    }

    @Test
    fun raw_drawing_is_not_active_while_paused_or_stopped() {
        input.start(view, board, emptyList())
        drawFrames()
        input.pause()
        assertThat(input.rawActive).isFalse()
        input.resume()
        assertLive()
        input.stop()
        assertThat(input.rawActive).isFalse()
    }

    @Test
    fun a_hold_released_while_paused_keeps_raw_drawing_off_until_resume() {
        input.start(view, board, emptyList())
        drawFrames()
        input.holdUi()
        input.pause()
        input.releaseUi()
        assertThat(input.rawActive).isFalse()
        assertThat(raw.on).isFalse()
        input.resume()
        assertLive()
    }

    @Test
    fun native_exclude_stops_covering_the_board_when_the_chrome_hides() {
        input.start(view, board, listOf(tabBar))
        drawFrames()
        assertThat(raw.exclude).containsExactly(tabBar)
        input.start(view, board, emptyList())
        assertLive()
        assertThat(raw.exclude.none { Rect.intersects(it, board) }).isTrue()
    }
}
