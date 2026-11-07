package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.doc.COLOUR_MARKER
import kotlinx.serialization.Serializable

/** A colour over display offsets `[from, to)` of a [TextRun]. */
@Serializable
data class ColourSpan(
    val from: Int,
    val to: Int,
    val color: PenColor,
)

/** Result of [SourceMap.stripWithColours]: the text the board shows, its offset map and its colour spans. */
data class StrippedLine(
    val display: String,
    val map: SourceMap,
    val spans: List<ColourSpan>,
)

/**
 * Maps display offsets of a stripped markdown line back to offsets in the source line. [displayToSource] has
 * `display.length + 1` entries: entry `i` is the source offset of display char `i`, the last entry is the source
 * length. Entries never decrease.
 */
@Serializable
class SourceMap(
    val displayToSource: IntArray,
) {
    /** The source offset of display offset [displayOffset]; out-of-range offsets clamp to the ends. */
    fun toSource(displayOffset: Int): Int = displayToSource[displayOffset.coerceIn(0, displayToSource.lastIndex)]

    override fun equals(other: Any?): Boolean = other is SourceMap && displayToSource.contentEquals(other.displayToSource)

    override fun hashCode(): Int = displayToSource.contentHashCode()

    override fun toString(): String = "SourceMap(${displayToSource.joinToString(",")})"

    companion object {
        private val HEADING = Regex("^\\s*#{1,6}\\s+")
        private const val CLOSE = "[[/]]"

        /** [stripWithColours] without the colour spans. */
        fun strip(sourceLine: String): Pair<String, SourceMap> = stripWithColours(sourceLine).let { it.display to it.map }

        /**
         * Removes inline markdown (`**`, `*`, `_` at word edges, backticks, leading `#`s) and the translator's
         * `[[colour]]...[[/]]` markers from one source line. Bullets and numbers stay as typed text. An unclosed
         * colour marker colours to the end of the line. Other `[[...]]` markers stay plain text.
         */
        fun stripWithColours(sourceLine: String): StrippedLine {
            val display = StringBuilder()
            val offsets = ArrayList<Int>()
            val spans = ArrayList<ColourSpan>()
            var open: Pair<Int, PenColor>? = null
            var i = HEADING.find(sourceLine)?.range?.let { it.last + 1 } ?: 0
            val bulletEnd = bulletEnd(sourceLine, i)

            fun closeSpan() {
                open?.let { (from, color) -> if (display.length > from) spans += ColourSpan(from, display.length, color) }
                open = null
            }
            while (i < sourceLine.length) {
                val marker = COLOUR_MARKER.matchAt(sourceLine, i)
                when {
                    marker != null -> {
                        closeSpan()
                        val name = marker.groupValues[1].lowercase()
                        open = display.length to PenColor.entries.first { it.wire == name }
                        i = marker.range.last + 1
                    }

                    sourceLine.startsWith(CLOSE, i) -> {
                        closeSpan()
                        i += CLOSE.length
                    }

                    i >= bulletEnd && isMarkup(sourceLine, i) -> {
                        i++
                    }

                    else -> {
                        display.append(sourceLine[i])
                        offsets += i
                        i++
                    }
                }
            }
            closeSpan()
            offsets += sourceLine.length
            return StrippedLine(display.toString(), SourceMap(offsets.toIntArray()), spans)
        }

        /** End of a leading `* ` bullet (kept as typed text); [from] when there is none. */
        private fun bulletEnd(
            line: String,
            from: Int,
        ): Int {
            var p = from
            while (p < line.length && line[p] == ' ') p++
            return if (p + 1 < line.length && line[p] == '*' && line[p + 1] == ' ') p + 2 else from
        }

        private fun isMarkup(
            line: String,
            i: Int,
        ): Boolean =
            when (line[i]) {
                '*', '`' -> true
                '_' -> !(i > 0 && i + 1 < line.length && line[i - 1].isLetterOrDigit() && line[i + 1].isLetterOrDigit())
                else -> false
            }
    }
}
