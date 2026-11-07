package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.PenColor
import org.junit.Test

class SourceMapTest {
    @Test
    fun strips_bold_and_maps_offsets_back() {
        val src = "buy **fresh** milk"
        val s = SourceMap.stripWithColours(src)
        assertThat(s.display).isEqualTo("buy fresh milk")
        // "milk" starts at display 10 and source 14
        assertThat(s.map.toSource(s.display.indexOf("milk"))).isEqualTo(src.indexOf("milk"))
        assertThat(s.map.toSource(s.display.indexOf("fresh"))).isEqualTo(src.indexOf("fresh"))
        assertThat(s.map.toSource(s.display.length)).isEqualTo(src.length)
    }

    @Test
    fun colour_markers_become_spans() {
        val src = "see [[red]]this[[/]] now"
        val s = SourceMap.stripWithColours(src)
        assertThat(s.display).isEqualTo("see this now")
        assertThat(s.spans).containsExactly(ColourSpan(4, 8, PenColor.RED))
        assertThat(s.map.toSource(4)).isEqualTo(src.indexOf("this"))
        assertThat(s.map.toSource(8)).isEqualTo(src.indexOf("now") - 1)
    }

    @Test
    fun unclosed_colour_marker_runs_to_the_end_of_the_line() {
        val s = SourceMap.stripWithColours("a [[blue]]b c")
        assertThat(s.display).isEqualTo("a b c")
        assertThat(s.spans).containsExactly(ColourSpan(2, 5, PenColor.BLUE))
    }

    @Test
    fun other_double_bracket_markers_stay_plain_text() {
        val s = SourceMap.stripWithColours("odd [[?word]] here")
        assertThat(s.display).isEqualTo("odd [[?word]] here")
        assertThat(s.spans).isEmpty()
    }

    @Test
    fun heading_hashes_removed() {
        val s = SourceMap.stripWithColours("## Shopping")
        assertThat(s.display).isEqualTo("Shopping")
        assertThat(s.map.toSource(0)).isEqualTo(3)
    }

    @Test
    fun bullet_kept_as_typed_text() {
        assertThat(SourceMap.stripWithColours("- milk").display).isEqualTo("- milk")
        assertThat(SourceMap.stripWithColours("* milk").display).isEqualTo("* milk")
        assertThat(SourceMap.stripWithColours("1. milk").display).isEqualTo("1. milk")
    }

    @Test
    fun emphasis_code_and_underscores() {
        val s = SourceMap.stripWithColours("*a* _b_ `c` snake_case")
        assertThat(s.display).isEqualTo("a b c snake_case")
    }

    @Test
    fun strip_returns_display_and_map() {
        val (display, map) = SourceMap.strip("**x**")
        assertThat(display).isEqualTo("x")
        assertThat(map.toSource(0)).isEqualTo(2)
    }

    @Test
    fun round_trip_every_offset_monotonic() {
        val src = "## **a** [[green]]b *c*[[/]] `d` - e_f"
        val s = SourceMap.stripWithColours(src)
        assertThat(s.map.displayToSource).hasLength(s.display.length + 1)
        var prev = -1
        for (i in 0..s.display.length) {
            val o = s.map.toSource(i)
            assertThat(o).isAtLeast(prev)
            assertThat(o).isAtMost(src.length)
            prev = o
        }
        for (i in s.display.indices) {
            assertThat(src[s.map.toSource(i)]).isEqualTo(s.display[i])
        }
    }

    @Test
    fun out_of_range_offsets_clamp() {
        val s = SourceMap.stripWithColours("ab")
        assertThat(s.map.toSource(-3)).isEqualTo(0)
        assertThat(s.map.toSource(99)).isEqualTo(2)
    }

    @Test
    fun equal_maps_compare_by_content() {
        assertThat(SourceMap(intArrayOf(0, 1))).isEqualTo(SourceMap(intArrayOf(0, 1)))
    }
}
