package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SseParserTest {
    private fun parse(vararg lines: String): List<SseItem> {
        val parser = SseParser()
        return lines.mapNotNull { parser.feed(it) }
    }

    @Test
    fun parses_multiline_data_id_and_comments() {
        val items = parse(": keepalive", "", "id: 7", "event: message", "data: {\"a\":", "data: 1}", "", "data:x", "")
        assertThat(items)
            .containsExactly(
                SseItem.Comment("keepalive"),
                SseItem.Message("7", "message", "{\"a\":\n1}"),
                SseItem.Message("7", null, "x"),
            ).inOrder()
    }

    @Test
    fun blank_line_without_data_dispatches_nothing() {
        assertThat(parse("id: 3", "")).isEmpty()
    }
}
