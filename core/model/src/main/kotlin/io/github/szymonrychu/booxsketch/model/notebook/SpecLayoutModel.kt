package io.github.szymonrychu.booxsketch.model.notebook

/** Where a spec line came from (the connector's `origin`); a line with no origin is null. */
sealed interface LineOrigin {
    /** Transcribed from these segments of an upload. */
    data class Segments(
        val ids: List<String>,
    ) : LineOrigin

    /** The same line as [lineId] of the base version. */
    data class Kept(
        val lineId: String,
    ) : LineOrigin

    /** Written by the edit op [id]. */
    data class Op(
        val id: String,
    ) : LineOrigin
}

/** One markdown source line (1-based [sourceLine]) of a spec version and its stable id. */
data class SpecLineModel(
    val lineId: String,
    val sourceLine: Int,
    val origin: LineOrigin?,
)

/** What the translator reported for one edit op. */
data class OpStatusModel(
    val id: String,
    val applied: Boolean,
    val note: String? = null,
)

/** The model mirror of the connector's `SpecLayout`; the app maps the DTO to it. */
data class SpecLayoutModel(
    val lines: List<SpecLineModel>,
    val ops: List<OpStatusModel> = emptyList(),
    val baseVersion: Int? = null,
    val revertedFrom: Int? = null,
)

/** Width in page px of [text] drawn at [scale] of the board's text size; the app measures with its `Paint`. */
fun interface TextMeasure {
    fun width(
        text: String,
        scale: Float,
    ): Float
}
