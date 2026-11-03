package io.github.szymonrychu.booxsketch.model

import kotlinx.serialization.Serializable

/** One sampled pen position. Tilt is kept locally only; it is not part of the wire format. */
@Serializable
data class Point(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tMs: Long,
    val tiltX: Float? = null,
    val tiltY: Float? = null,
)

/** Brush kinds; [wire] is the name used in the connector's strokes.json. */
@Serializable
enum class Tool(
    val wire: String,
) {
    FOUNTAIN("fountain"),
    BRUSH("brush"),
    BALLPOINT("ballpoint"),
    CHARCOAL("charcoal"),
    CHARCOAL_V2("charcoal_v2"),
    MARKER("marker"),
}

@Serializable
data class Stroke(
    val id: String,
    val tool: Tool,
    val width: Float,
    val points: List<Point>,
)

/** Drawing surface size in pixels and its density. */
@Serializable
data class Canvas(
    val w: Int,
    val h: Int,
    val dpi: Float,
)
