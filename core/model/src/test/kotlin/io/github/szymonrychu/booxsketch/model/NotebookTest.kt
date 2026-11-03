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
}
