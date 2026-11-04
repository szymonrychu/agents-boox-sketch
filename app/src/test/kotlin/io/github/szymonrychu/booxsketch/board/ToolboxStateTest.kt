package io.github.szymonrychu.booxsketch.board

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolboxStateTest {
    @Test
    fun first_tap_selects_without_popup() {
        val s = ToolboxState()
        assertThat(s.tap(ToolPopup.PEN, alreadySelected = false)).isEqualTo(TapResult.SelectOnly)
        assertThat(s.popup).isNull()
    }

    @Test
    fun second_tap_on_selected_opens_popup() {
        val s = ToolboxState()
        assertThat(s.tap(ToolPopup.PEN, alreadySelected = true)).isEqualTo(TapResult.TogglePopup)
        assertThat(s.popup).isEqualTo(ToolPopup.PEN)
    }

    @Test
    fun third_tap_closes_popup() {
        val s = ToolboxState()
        s.tap(ToolPopup.ERASER, alreadySelected = true)
        s.tap(ToolPopup.ERASER, alreadySelected = true)
        assertThat(s.popup).isNull()
    }

    @Test
    fun tap_other_tool_closes_popup_and_selects() {
        val s = ToolboxState()
        s.tap(ToolPopup.PEN, alreadySelected = true)
        assertThat(s.tap(ToolPopup.SHAPE, alreadySelected = false)).isEqualTo(TapResult.SelectOnly)
        assertThat(s.popup).isNull()
    }

    @Test
    fun colour_pages_send_always_toggle() {
        val s = ToolboxState()
        for (p in listOf(ToolPopup.COLOUR, ToolPopup.PAGES, ToolPopup.SEND)) {
            assertThat(s.tap(p, alreadySelected = false)).isEqualTo(TapResult.TogglePopup)
            assertThat(s.popup).isEqualTo(p)
            s.tap(p, alreadySelected = false)
            assertThat(s.popup).isNull()
        }
    }

    @Test
    fun switching_between_always_toggle_popups_replaces_the_popup() {
        val s = ToolboxState()
        s.tap(ToolPopup.COLOUR, alreadySelected = false)
        s.tap(ToolPopup.SEND, alreadySelected = false)
        assertThat(s.popup).isEqualTo(ToolPopup.SEND)
    }

    @Test
    fun selecting_eraser_closes_pen_popup() {
        val s = ToolboxState()
        s.tap(ToolPopup.PEN, alreadySelected = true)
        s.onToolChanged(ToolPopup.ERASER)
        assertThat(s.popup).isNull()
    }

    @Test
    fun shape_popup_belongs_to_selected_shape_only() {
        val s = ToolboxState()
        s.tap(ToolPopup.SHAPE, alreadySelected = true)
        s.onToolChanged(ToolPopup.SHAPE)
        assertThat(s.popup).isEqualTo(ToolPopup.SHAPE)
        s.onToolChanged(ToolPopup.PEN)
        assertThat(s.popup).isNull()
    }

    @Test
    fun a_tool_change_never_closes_colour_pages_or_send() {
        val s = ToolboxState()
        for (p in listOf(ToolPopup.COLOUR, ToolPopup.PAGES, ToolPopup.SEND)) {
            s.tap(p, alreadySelected = false)
            s.onToolChanged(ToolPopup.ERASER)
            assertThat(s.popup).isEqualTo(p)
            s.close()
        }
    }

    @Test
    fun popup_changes_go_through_the_gate_once_each() {
        val log = mutableListOf<ToolPopup?>()
        val s =
            ToolboxState { target, apply ->
                log += target
                apply()
            }
        s.tap(ToolPopup.PEN, alreadySelected = true)
        s.close()
        s.close()
        s.onToolChanged(ToolPopup.PEN)
        assertThat(log).containsExactly(ToolPopup.PEN, null).inOrder()
    }
}
