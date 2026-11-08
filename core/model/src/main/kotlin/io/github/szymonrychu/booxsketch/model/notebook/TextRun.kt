package io.github.szymonrychu.booxsketch.model.notebook

import kotlinx.serialization.Serializable

/**
 * One rendered markdown source line placed on a notebook page. [display] is the source line with inline markup
 * stripped, [sourceMap] maps its offsets back to the source line, [wrapRows] is the number of rows a wrapped line
 * takes, [scale] the text scale (always [RunPlacer.TEXT_SCALE] once placed), [heading] marks a markdown heading (drawn bold).
 * [column] is the width in page px the placement fitted and wrapped the line in (0: up to the margin), so the text
 * layer wraps at the same offsets. [source] is the markdown source line itself (the text an op's quote is cut from);
 * it defaults to [display] for a line without markup.
 */
@Serializable
data class TextRun(
    val lineId: String,
    val specVersion: Int,
    val sourceLine: Int,
    val row: Int,
    val x0: Float,
    val wrapRows: Int = 1,
    val scale: Float = 1f,
    val display: String,
    val colourSpans: List<ColourSpan>,
    val sourceMap: SourceMap,
    val sourceStrokeIds: List<String>,
    val heading: Boolean = false,
    val column: Float = 0f,
    val source: String = display,
)

/** A segment sent with an upload and not yet answered by a spec version; the app keeps its anchor. */
@Serializable
data class PendingSegment(
    val clientId: String,
    val segmentId: String,
    val row: Int,
    val x0: Float,
    val x1: Float,
    val strokeIds: List<String>,
    /** The spec version the upload was based on (0 when none): a newer version that does not answer it releases it. */
    val baseVersion: Int = 0,
)
