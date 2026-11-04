package io.github.szymonrychu.booxsketch.board

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for android.graphics.Rect (a stub on the plain JVM); no UI is composed.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChromeHoldsTest {
    private val input = FreezingInput()
    private val holds = ChromeHolds(input)
    private val panelRect = Rect(700, 0, 1000, 900)
    private val dockRect = Rect(0, 800, 600, 900)
    private val indicator = Rect(0, 0, 48, 48)

    @Test
    fun two_overlays_in_one_tap_hold_once_and_release_once() {
        var applied = 0
        holds.change("panel", false) { applied++ }
        holds.change("dock", false) { applied++ }
        assertThat(applied).isEqualTo(2)
        assertThat(holds.settled).isFalse()

        holds.report("panel", false, listOf(Rect(952, 400, 1000, 448)))
        assertThat(holds.settled).isFalse()
        holds.report("dock", false, listOf(indicator))
        assertThat(holds.settled).isTrue()

        holds.finishIfSettled()
        holds.finishIfSettled()
        assertThat(input.log).containsExactly("hold", "release").inOrder()
        assertThat(holds.settled).isFalse()
    }

    @Test
    fun popup_close_settles_with_empty_rects_when_needsRects_false() {
        holds.change("popup", false, needsRects = false) { }
        holds.report("popup", false, emptyList())
        assertThat(holds.settled).isTrue()
        holds.finishIfSettled()
        assertThat(input.log).containsExactly("hold", "release").inOrder()
    }

    @Test
    fun unsettled_while_one_key_reports_empty() {
        holds.change("panel", true) { }
        holds.change("dock", true) { }
        holds.report("panel", true, listOf(panelRect))
        holds.report("dock", true, emptyList())
        assertThat(holds.settled).isFalse()
        holds.finishIfSettled()
        assertThat(input.log).containsExactly("hold")

        holds.report("dock", true, listOf(dockRect))
        assertThat(holds.settled).isTrue()
        holds.finishIfSettled()
        assertThat(input.log).containsExactly("hold", "release").inOrder()
    }

    @Test
    fun release_without_pending_does_not_release() {
        holds.release()
        holds.finishIfSettled()
        assertThat(input.log).isEmpty()

        holds.change("dock", false) { }
        holds.release()
        holds.release()
        assertThat(input.log).containsExactly("hold", "release").inOrder()
    }

    @Test
    fun hidden_state_needs_nonempty_indicator_rect() {
        holds.change("dock", false) { }
        holds.report("dock", false, emptyList())
        assertThat(holds.settled).isFalse()
        holds.report("dock", false, listOf(indicator))
        assertThat(holds.settled).isTrue()
    }

    @Test
    fun rects_are_the_union_of_every_key_in_first_report_order() {
        holds.report("dock", true, listOf(dockRect))
        holds.report("panel", false, listOf(indicator))
        assertThat(holds.rects).containsExactly(dockRect, indicator).inOrder()
        holds.report("dock", false, emptyList())
        assertThat(holds.rects).containsExactly(indicator)
    }

    @Test
    fun a_report_of_the_other_state_does_not_settle() {
        holds.report("dock", true, listOf(dockRect))
        holds.change("dock", false) { }
        assertThat(holds.settled).isFalse()
    }
}
