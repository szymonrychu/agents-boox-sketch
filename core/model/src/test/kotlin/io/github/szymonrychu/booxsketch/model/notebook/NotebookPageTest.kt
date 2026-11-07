package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.serialization.json.Json
import org.junit.Test

class NotebookPageTest {
    private fun stroke(id: String) = Stroke(id, Tool.BALLPOINT, 2f, emptyList())

    @Test
    fun visible_strokes_skip_consumed() {
        val page = Page(listOf(stroke("a"), stroke("b"), stroke("c")), true, consumed = setOf("b"))
        assertThat(page.visibleStrokes.map { it.id }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun page_defaults_are_empty() {
        val page = Page(emptyList(), false)
        assertThat(page.text).isEmpty()
        assertThat(page.consumed).isEmpty()
        assertThat(page.pendingSegments).isEmpty()
        assertThat(page.pendingOps).isEmpty()
    }

    @Test
    fun text_run_round_trips_through_json() {
        val stripped = SourceMap.stripWithColours("**a** b")
        val run =
            TextRun(
                lineId = "L1",
                specVersion = 2,
                sourceLine = 3,
                row = 4,
                x0 = 53f,
                display = stripped.display,
                colourSpans = stripped.spans,
                sourceMap = stripped.map,
                sourceStrokeIds = listOf("s1"),
            )
        val op =
            EditOp(
                id = "o1",
                kind = OpKind.REPLACE,
                target = OpTarget("L1", 0, 1, "a"),
                at = OpAt("L1", 1, AtMode.INLINE),
                strokeIds = listOf("s2"),
                rect = listOf(1f, 2f, 3f, 4f),
                region = RegionRef("r1", "region.0.0.png", null),
            )
        val page =
            Page(
                emptyList(),
                false,
                text = listOf(run),
                consumed = setOf("s1"),
                pendingSegments = listOf(PendingSegment("c1", "p0-r0-0", 0, 10f, 20f, listOf("s3"))),
                pendingOps = listOf(PendingOp("c1", op)),
            )
        val json = Json { encodeDefaults = false }
        val back = json.decodeFromString(Page.serializer(), json.encodeToString(Page.serializer(), page))
        assertThat(back).isEqualTo(page)
    }
}
