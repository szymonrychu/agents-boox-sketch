package io.github.szymonrychu.booxsketch.model.doc

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.PenColor
import org.junit.Test

class ColourMarkersTest {
    @Test
    fun plain_text_is_one_uncoloured_span() {
        assertThat(splitColourMarkers("Login button")).containsExactly(MarkerSpan("Login button", null))
    }

    @Test
    fun a_marker_becomes_a_labelled_coloured_span() {
        assertThat(splitColourMarkers("[[red]] Login button"))
            .containsExactly(MarkerSpan("[red]", PenColor.RED), MarkerSpan(" Login button", null))
            .inOrder()
    }

    @Test
    fun every_pen_colour_is_recognised() {
        for (c in PenColor.entries) {
            assertThat(splitColourMarkers("[[${c.wire}]]")).containsExactly(MarkerSpan("[${c.wire}]", c))
        }
    }

    @Test
    fun several_markers_in_one_text_keep_their_order() {
        val spans = splitColourMarkers("- [[blue]] one\n- [[green]] two")
        assertThat(spans.map { it.colour }).containsExactly(null, PenColor.BLUE, null, PenColor.GREEN, null).inOrder()
        assertThat(spans.joinToString("") { it.text }).isEqualTo("- [blue] one\n- [green] two")
    }

    @Test
    fun the_name_is_case_insensitive() {
        assertThat(splitColourMarkers("[[Red]]")).containsExactly(MarkerSpan("[red]", PenColor.RED))
    }

    @Test
    fun uncertain_and_illegible_markers_and_unknown_names_stay_as_written() {
        for (text in listOf("[[?word]]", "[[?a|b]]", "[[illegible]]", "[[pink]]", "[[red]", "[red]", "[[ red ]]")) {
            assertThat(splitColourMarkers(text)).containsExactly(MarkerSpan(text, null))
        }
    }

    @Test
    fun empty_text_has_no_spans() {
        assertThat(splitColourMarkers("")).isEmpty()
    }

    @Test
    fun adjacent_markers_become_adjacent_spans_without_empty_text() {
        assertThat(splitColourMarkers("[[red]][[blue]]x[[green]]"))
            .containsExactly(
                MarkerSpan("[red]", PenColor.RED),
                MarkerSpan("[blue]", PenColor.BLUE),
                MarkerSpan("x", null),
                MarkerSpan("[green]", PenColor.GREEN),
            ).inOrder()
    }
}
