package io.github.szymonrychu.booxsketch.model.doc

import io.github.szymonrychu.booxsketch.model.PenColor

/** A run of text; [colour] is set for the label of a `[[colour]]` marker, null for ordinary text. */
data class MarkerSpan(
    val text: String,
    val colour: PenColor?,
)

internal val COLOUR_MARKER = Regex("\\[\\[(${PenColor.entries.joinToString("|") { it.wire }})]]", RegexOption.IGNORE_CASE)

/**
 * Splits [text] around the translator's ink colour markers (`[[red]]`, also black, blue, green, orange, purple). A
 * marker becomes the span `[red]` (the name stays readable without colour, which matters on e-ink); every other
 * `[[...]]` marker, such as `[[?word]]` or `[[illegible]]`, stays plain text.
 */
fun splitColourMarkers(text: String): List<MarkerSpan> {
    val out = ArrayList<MarkerSpan>()
    var from = 0
    for (m in COLOUR_MARKER.findAll(text)) {
        if (m.range.first > from) out += MarkerSpan(text.substring(from, m.range.first), null)
        val name = m.groupValues[1].lowercase()
        out += MarkerSpan("[$name]", PenColor.entries.first { it.wire == name })
        from = m.range.last + 1
    }
    if (from < text.length) out += MarkerSpan(text.substring(from), null)
    return out
}
