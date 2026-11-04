package io.github.szymonrychu.booxsketch.documents

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageSwipeTest {
    private val swipe = ThresholdPageSwipe(minTravelPx = 120f)

    @Test
    fun swipe_right_to_left_is_next() = assertThat(swipe.classify(-300f, 10f, 200)).isEqualTo(SwipeDir.NEXT)

    @Test
    fun swipe_left_to_right_is_prev() = assertThat(swipe.classify(300f, -10f, 200)).isEqualTo(SwipeDir.PREV)

    @Test
    fun swipe_mostly_vertical_is_none() = assertThat(swipe.classify(-150f, 400f, 200)).isEqualTo(SwipeDir.NONE)

    @Test
    fun short_swipe_is_none() = assertThat(swipe.classify(-100f, 0f, 100)).isEqualTo(SwipeDir.NONE)

    @Test
    fun slow_drag_is_none() = assertThat(swipe.classify(-300f, 0f, 5_000)).isEqualTo(SwipeDir.NONE)
}
