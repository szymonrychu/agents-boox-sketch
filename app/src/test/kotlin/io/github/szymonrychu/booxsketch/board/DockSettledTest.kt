package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DockSettledTest {
    private val rect = Rect(0, 0, 10, 10)

    @Test
    fun nothing_pending_is_never_settled() {
        assertThat(dockSettled(null, true, listOf(rect))).isFalse()
    }

    @Test
    fun matching_state_with_a_rect_is_settled() {
        assertThat(dockSettled(false, false, listOf(rect))).isTrue()
        assertThat(dockSettled(true, true, listOf(rect))).isTrue()
    }

    @Test
    fun matching_state_with_no_rect_yet_is_not_settled() {
        assertThat(dockSettled(false, false, emptyList())).isFalse()
        assertThat(dockSettled(true, true, emptyList())).isFalse()
    }

    @Test
    fun report_of_the_other_state_is_not_settled() {
        assertThat(dockSettled(false, true, listOf(rect))).isFalse()
        assertThat(dockSettled(true, false, listOf(rect))).isFalse()
    }
}
