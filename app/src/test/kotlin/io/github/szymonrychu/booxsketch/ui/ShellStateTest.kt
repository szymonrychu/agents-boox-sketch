package io.github.szymonrychu.booxsketch.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellStateTest {
    @Test
    fun starts_on_board_with_chrome_visible() {
        val state = ShellState()
        assertThat(state.selected).isEqualTo(BOARD_TAB_ID)
        assertThat(state.chromeVisible).isTrue()
        assertThat(state.strokeTick).isEqualTo(0)
        assertThat(state.dockSize).isNull()
    }

    @Test
    fun hide_then_show() {
        val state = ShellState()
        state.hide()
        assertThat(state.chromeVisible).isFalse()
        state.show()
        assertThat(state.chromeVisible).isTrue()
    }

    @Test
    fun stroke_while_hidden_does_not_arm() {
        val state = ShellState()
        state.hide()
        state.onStrokeCommitted()
        assertThat(state.strokeTick).isEqualTo(0)
    }

    @Test
    fun stroke_while_visible_arms() {
        val state = ShellState()
        state.onStrokeCommitted()
        assertThat(state.strokeTick).isEqualTo(1)
    }

    @Test
    fun selecting_board_shows_chrome() {
        val state = ShellState(selected = "spec", chromeVisible = false)
        state.select(BOARD_TAB_ID)
        assertThat(state.selected).isEqualTo(BOARD_TAB_ID)
        assertThat(state.chromeVisible).isTrue()
    }

    @Test
    fun selecting_other_tab_keeps_chrome_flag() {
        val state = ShellState(chromeVisible = false)
        state.select("spec")
        assertThat(state.selected).isEqualTo("spec")
        assertThat(state.chromeVisible).isFalse()
    }

    @Test
    fun panel_is_hidden_by_default() {
        assertThat(ShellState().panelVisible).isFalse()
    }

    @Test
    fun hide_hides_chrome_and_panel() {
        val state = ShellState()
        state.showPanel()
        state.hide()
        assertThat(state.chromeVisible).isFalse()
        assertThat(state.panelVisible).isFalse()
    }

    @Test
    fun show_panel_does_not_show_chrome() {
        val state = ShellState(chromeVisible = false)
        state.showPanel()
        assertThat(state.panelVisible).isTrue()
        assertThat(state.chromeVisible).isFalse()
        state.hidePanel()
        assertThat(state.panelVisible).isFalse()
    }

    @Test
    fun stroke_while_only_the_panel_is_visible_arms() {
        val state = ShellState(chromeVisible = false)
        state.showPanel()
        state.onStrokeCommitted()
        assertThat(state.strokeTick).isEqualTo(1)
    }
}
