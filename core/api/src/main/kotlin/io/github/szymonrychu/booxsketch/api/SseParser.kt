package io.github.szymonrychu.booxsketch.api

/** One parsed SSE item: a comment line (keepalive) or a dispatched message. */
sealed interface SseItem {
    data class Comment(
        val text: String,
    ) : SseItem

    data class Message(
        val id: String?,
        val event: String?,
        val data: String,
    ) : SseItem
}

/** Line-by-line SSE parser (WHATWG event-stream format). Feed lines without terminators; blank line dispatches. */
class SseParser {
    private var id: String? = null
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Consumes one line and returns the item it completes, or null. */
    fun feed(line: String): SseItem? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return SseItem.Comment(line.substring(1).trimStart(' '))
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> {
                if ('\u0000' !in value) id = value
            }

            "event" -> {
                event = value
            }

            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseItem? {
        val item = if (hasData) SseItem.Message(id, event, data.toString()) else null
        event = null
        data.setLength(0)
        hasData = false
        return item
    }
}
