package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class NotebookTest {
    private val canvas = Canvas(1404, 1872, 300f)

    private fun page(
        id: String,
        changed: Boolean = false,
    ) = Page(listOf(Stroke(id, Tool.BRUSH, 2f, emptyList())), changed)

    @Test
    fun delete_page_renumbers_and_keeps_one() {
        val n = Notebook(canvas, listOf(page("a"), page("b"), page("c")), 2)
        val d = n.deletePage(0)
        assertThat(d.pages.map { it.strokes[0].id }).containsExactly("b", "c").inOrder()
        assertThat(d.current).isEqualTo(1)
        val last = Notebook(canvas, listOf(page("a")), 0).deletePage(0)
        assertThat(last.pages).hasSize(1)
        assertThat(last.pages[0].strokes).isEmpty()
        assertThat(last.current).isEqualTo(0)
    }

    @Test
    fun delete_current_last_page_moves_selection_back() {
        val d = Notebook(canvas, listOf(page("a"), page("b")), 1).deletePage(1)
        assertThat(d.current).isEqualTo(0)
    }

    @Test
    fun mark_sent_clears_flag_only_for_given_pages() {
        val n = Notebook(canvas, listOf(page("a", true), page("b", true), page("c", true)), 0)
        val m = n.markSent(setOf(0, 2))
        assertThat(m.pages.map { it.changedSinceSend }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun add_page_appends_and_selects_it() {
        val n = Notebook(canvas, listOf(page("a")), 0).addPage()
        assertThat(n.pages).hasSize(2)
        assertThat(n.current).isEqualTo(1)
    }

    @Test
    fun notebook_json_roundtrip_keeps_tilt_and_ids() {
        val points = listOf(Point(1f, 2f, 0.7f, 99L, 0.1f, -0.2f), Point(3f, 4f, 0.3f, 120L))
        val s = Stroke("id-1", Tool.CHARCOAL_V2, 4.5f, points)
        val n = Notebook(canvas, listOf(Page(listOf(s), true)), 0)
        val back = Json.decodeFromString<Notebook>(Json.encodeToString(n))
        assertThat(back).isEqualTo(n)
        val first = back.pages[0].strokes[0]
        assertThat(first.id).isEqualTo("id-1")
        assertThat(first.points[0].tiltX).isEqualTo(0.1f)
    }

    @Test
    fun notebook_json_without_color_loads_black() {
        val json =
            """{"canvas":{"w":1404,"h":1872,"dpi":300.0},"pages":[{"strokes":[{"id":"old","tool":"MARKER","width":3.0,""" +
                """"points":[{"x":1.0,"y":2.0,"pressure":0.5,"tMs":7}]}],"changedSinceSend":true}],"current":0}"""
        val n = Json { ignoreUnknownKeys = true }.decodeFromString<Notebook>(json)
        val s = n.pages[0].strokes[0]
        assertThat(s.color).isEqualTo(PenColor.BLACK)
        assertThat(s.tool).isEqualTo(Tool.MARKER)
    }

    @Test
    fun stroke_color_and_shape_tool_roundtrip_in_notebook_json() {
        val pts = listOf(Point(1f, 2f, 0.5f, 1L), Point(5f, 6f, 0.5f, 2L))
        val strokes =
            listOf(
                Stroke("r", Tool.RECTANGLE, 2f, pts, PenColor.RED),
                Stroke("a", Tool.ARROW, 2f, pts, PenColor.PURPLE),
            )
        val n = Notebook(canvas, listOf(Page(strokes, true)), 0)
        val back = Json.decodeFromString<Notebook>(Json.encodeToString(n))
        assertThat(back).isEqualTo(n)
        assertThat(back.pages[0].strokes.map { it.color }).containsExactly(PenColor.RED, PenColor.PURPLE).inOrder()
        assertThat(back.pages[0].strokes.map { it.tool }).containsExactly(Tool.RECTANGLE, Tool.ARROW).inOrder()
    }
}
