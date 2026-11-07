package io.github.szymonrychu.booxsketch.model

import kotlinx.serialization.Serializable

/** One sampled pen position. Tilt is optional and travels in strokes.json version 3. */
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
    RECTANGLE("rectangle"),
    ARROW("arrow"),

    /** A dragged rectangle marking a picture: the ink inside it is sent as an image, not transcribed (spec 3.6). */
    IMAGE_REGION("image_region"),
    ;

    /** Shape tools are drawn from two dragged points, not from a free pen path. */
    val isShape: Boolean get() = this == RECTANGLE || this == ARROW || this == IMAGE_REGION

    /** Shapes drawn as an axis-aligned box between their two points. */
    val isBox: Boolean get() = this == RECTANGLE || this == IMAGE_REGION
}

/** Pen colours; [wire] is the name in strokes.json, [argb] the colour used for drawing. */
@Serializable
enum class PenColor(
    val wire: String,
    val argb: Int,
) {
    BLACK("black", 0xFF000000.toInt()),
    RED("red", 0xFFE00000.toInt()),
    BLUE("blue", 0xFF0050E0.toInt()),
    GREEN("green", 0xFF00A040.toInt()),
    ORANGE("orange", 0xFFFF8000.toInt()),
    PURPLE("purple", 0xFF8020C0.toInt()),
}

@Serializable
data class Stroke(
    val id: String,
    val tool: Tool,
    val width: Float,
    val points: List<Point>,
    val color: PenColor = PenColor.BLACK,
)

/** Drawing surface size in pixels and its density. */
@Serializable
data class Canvas(
    val w: Int,
    val h: Int,
    val dpi: Float,
)
