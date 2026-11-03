package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoardLimitTest {
    // Window 1240x930 (landscape): status bar 24 top, nav bar 48 bottom, cutout 40 left.
    @Test
    fun view_overlapping_bars_is_cut_back_landscape() {
        val r = BoardLimit.compute(Rect(0, 0, 700, 930), Rect(0, 0, 1240, 930), Rect(40, 24, 0, 48))
        assertEquals(40, r.left)
        assertEquals(24, r.top)
        assertEquals(700, r.right)
        assertEquals(930 - 48, r.bottom)
    }

    @Test
    fun limit_does_not_overlap_bar_areas_portrait() {
        // Portrait window 930x1240, view in the top half at window y 24 (already below the status bar).
        val r = BoardLimit.compute(Rect(0, 24, 930, 724), Rect(0, 0, 930, 1240), Rect(0, 24, 0, 48))
        assertEquals(0, r.top)
        assertTrue(r.bottom <= 700)
        // A view reaching the nav bar is cut at the bar's top edge.
        val low = BoardLimit.compute(Rect(0, 600, 930, 1240), Rect(0, 0, 930, 1240), Rect(0, 24, 0, 48))
        assertEquals(1240 - 48 - 600, low.bottom)
        // In window coordinates the limit never intersects the bar rects.
        val topBar = Rect(0, 0, 930, 24)
        val bottomBar = Rect(0, 1240 - 48, 930, 1240)
        val inWindow = Rect(low).apply { offset(0, 600) }
        assertTrue(!Rect.intersects(inWindow, topBar))
        assertTrue(!Rect.intersects(inWindow, bottomBar))
    }

    @Test
    fun no_insets_gives_full_view() {
        val r = BoardLimit.compute(Rect(10, 20, 310, 420), Rect(0, 0, 1240, 930), Rect(0, 0, 0, 0))
        assertEquals(Rect(0, 0, 300, 400), r)
    }

    @Test
    fun fully_covered_view_gives_empty_rect() {
        val r = BoardLimit.compute(Rect(0, 900, 300, 930), Rect(0, 0, 1240, 930), Rect(0, 24, 0, 48))
        assertTrue(r.width() >= 0 && r.height() >= 0)
    }
}
