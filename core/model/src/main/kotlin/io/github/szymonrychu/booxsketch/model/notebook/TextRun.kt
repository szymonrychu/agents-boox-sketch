package io.github.szymonrychu.booxsketch.model.notebook

import kotlinx.serialization.Serializable

/**
 * One rendered markdown source line placed on a notebook page. [display] is the source line with inline markup
 * stripped, [sourceMap] maps its offsets back to the source line, [wrapRows] is the number of rows a wrapped line
 * takes, [scale] the shrink factor (1 down to the 85 percent floor), [heading] marks a markdown heading (drawn bold).
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
)
