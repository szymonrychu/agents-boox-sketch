// one small draw function per glyph; IconKind and ToolIcon belong together
@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.model.Tool

/** The glyphs of the tool strip, popups and indicators. */
enum class IconKind {
    FOUNTAIN,
    BRUSH,
    BALLPOINT,
    CHARCOAL,
    CHARCOAL_V2,
    MARKER,
    ERASER,
    RECTANGLE,
    ARROW,
    COLOUR,
    UNDO,
    REDO,
    PAGES,
    SEND,
    HIDE,
    SHOW,
}

/** The icon of a tool: one per pen type, so the pen button shows the selected pen. */
fun Tool.iconKind(): IconKind =
    when (this) {
        Tool.FOUNTAIN -> IconKind.FOUNTAIN
        Tool.BRUSH -> IconKind.BRUSH
        Tool.BALLPOINT -> IconKind.BALLPOINT
        Tool.CHARCOAL -> IconKind.CHARCOAL
        Tool.CHARCOAL_V2 -> IconKind.CHARCOAL_V2
        Tool.MARKER -> IconKind.MARKER
        Tool.RECTANGLE -> IconKind.RECTANGLE
        Tool.ARROW -> IconKind.ARROW
    }

/** Design grid of every glyph, in dp (and in path units). */
private const val GRID = 24f
private const val LINE = 2f

/**
 * A tool glyph drawn on a Canvas from paths on a 24 unit grid with 2 unit strokes: pure [tint] lines
 * and fills, no grey, so it reads on e-ink. [fill] colours the dot of [IconKind.COLOUR] (ignored
 * by the other kinds). Size it with [modifier]; the default is 24 dp.
 */
@Composable
fun ToolIcon(
    kind: IconKind,
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
    fill: Color? = null,
) {
    Canvas(modifier.size(24.dp)) {
        val k = size.minDimension / GRID
        scale(k, k, pivot = Offset.Zero) { draw(kind, tint, fill) }
    }
}

private val line = Stroke(width = LINE, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.draw(
    kind: IconKind,
    c: Color,
    fill: Color?,
) {
    when (kind) {
        IconKind.FOUNTAIN, IconKind.BRUSH, IconKind.BALLPOINT, IconKind.CHARCOAL, IconKind.CHARCOAL_V2, IconKind.MARKER -> pen(kind, c)
        IconKind.ERASER -> eraser(c)
        IconKind.RECTANGLE -> drawRect(c, Offset(3f, 6f), Size(18f, 12f), style = line)
        IconKind.ARROW -> arrow(c)
        IconKind.COLOUR -> colour(c, fill)
        IconKind.UNDO -> undo(c)
        IconKind.REDO -> scale(-1f, 1f, pivot = Offset(GRID / 2, GRID / 2)) { undo(c) }
        IconKind.PAGES -> pages(c)
        IconKind.SEND -> send(c)
        IconKind.HIDE -> chevron(c, left = true)
        IconKind.SHOW -> chevron(c, left = false)
    }
}

private fun path(block: Path.() -> Unit) = Path().apply(block)

/** A diagonal pen (barrel and nib) over a sample of the stroke it draws. */
private fun DrawScope.pen(
    kind: IconKind,
    c: Color,
) {
    drawLine(c, Offset(20f, 3f), Offset(10f, 13f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawPath(
        path {
            moveTo(9f, 11f)
            lineTo(13f, 15f)
            lineTo(5f, 17f)
            close()
        },
        c,
        style = Fill,
    )
    when (kind) {
        IconKind.FOUNTAIN -> {
            drawPath(
                path {
                    moveTo(9f, 21f)
                    lineTo(15f, 19.5f)
                    lineTo(22f, 21f)
                    lineTo(15f, 22.5f)
                    close()
                },
                c,
                style = Fill,
            )
        }

        IconKind.BRUSH -> {
            drawPath(
                path {
                    moveTo(8f, 21.5f)
                    lineTo(14f, 18.5f)
                    lineTo(22f, 21.5f)
                    lineTo(14f, 24f)
                    close()
                },
                c,
                style = Fill,
            )
        }

        IconKind.BALLPOINT -> {
            drawLine(c, Offset(9f, 21f), Offset(22f, 21f), strokeWidth = 1f, cap = StrokeCap.Butt)
        }

        IconKind.CHARCOAL -> {
            for (i in 0..4) drawCircle(c, 1.1f, Offset(10f + i * 3f, 21f))
        }

        IconKind.CHARCOAL_V2 -> {
            for (i in 0..4) {
                drawCircle(c, 1f, Offset(10f + i * 3f, 20f))
                drawCircle(c, 1f, Offset(11.5f + i * 3f, 23f))
            }
        }

        else -> {
            drawLine(c, Offset(9f, 21f), Offset(22f, 21f), strokeWidth = 4.5f, cap = StrokeCap.Butt)
        }
    }
}

private fun DrawScope.eraser(c: Color) {
    drawPath(
        path {
            moveTo(3f, 15f)
            lineTo(13f, 5f)
            lineTo(21f, 13f)
            lineTo(11f, 23f)
            close()
        },
        c,
        style = line,
    )
    drawLine(c, Offset(8f, 10f), Offset(16f, 18f), strokeWidth = LINE, cap = StrokeCap.Round)
}

private fun DrawScope.arrow(c: Color) {
    drawLine(c, Offset(4f, 20f), Offset(20f, 4f), strokeWidth = LINE, cap = StrokeCap.Round)
    drawPath(
        path {
            moveTo(12f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 12f)
        },
        c,
        style = line,
    )
}

private fun DrawScope.colour(
    c: Color,
    fill: Color?,
) {
    drawCircle(c, 9f, Offset(12f, 12f), style = line)
    if (fill != null) drawCircle(fill, 6f, Offset(12f, 12f), style = Fill)
    drawCircle(c, 6f, Offset(12f, 12f), style = Stroke(width = 1f))
}

private fun DrawScope.undo(c: Color) {
    drawPath(
        path {
            moveTo(9f, 5f)
            lineTo(4f, 10f)
            lineTo(9f, 15f)
        },
        c,
        style = line,
    )
    drawPath(
        path {
            moveTo(4f, 10f)
            lineTo(13f, 10f)
            cubicTo(18f, 10f, 20f, 13f, 20f, 16f)
            cubicTo(20f, 19f, 18f, 21f, 14f, 21f)
        },
        c,
        style = line,
    )
}

private fun DrawScope.pages(c: Color) {
    drawRect(c, Offset(5f, 3f), Size(14f, 18f), style = line)
    drawLine(c, Offset(8f, 9f), Offset(16f, 9f), strokeWidth = LINE, cap = StrokeCap.Round)
    drawLine(c, Offset(8f, 13f), Offset(16f, 13f), strokeWidth = LINE, cap = StrokeCap.Round)
    drawLine(c, Offset(8f, 17f), Offset(13f, 17f), strokeWidth = LINE, cap = StrokeCap.Round)
}

private fun DrawScope.send(c: Color) {
    drawPath(
        path {
            moveTo(3f, 11f)
            lineTo(21f, 3f)
            lineTo(14f, 21f)
            lineTo(11f, 13f)
            close()
        },
        c,
        style = line,
    )
    drawLine(c, Offset(11f, 13f), Offset(21f, 3f), strokeWidth = LINE, cap = StrokeCap.Round)
}

private fun DrawScope.chevron(
    c: Color,
    left: Boolean,
) {
    val x0 = if (left) 15f else 9f
    val x1 = if (left) 8f else 16f
    drawPath(
        path {
            moveTo(x0, 5f)
            lineTo(x1, 12f)
            lineTo(x0, 19f)
        },
        c,
        style = line,
    )
}
