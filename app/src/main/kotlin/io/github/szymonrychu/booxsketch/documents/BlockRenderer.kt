package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import io.github.szymonrychu.booxsketch.model.doc.Block
import io.github.szymonrychu.booxsketch.model.doc.splitColourMarkers

/** One measured block that knows how to draw a range of its text lines. */
sealed interface RenderedBlock {
    val block: Block

    /** Draws lines [from] until [to] at the top-left of [canvas] (the caller translates). */
    fun draw(
        canvas: Canvas,
        from: Int,
        to: Int,
    )
}

/** A block of wrapped text; it can be cut between lines. */
class TextBlock(
    private val layout: StaticLayout,
    private val spacingAfter: Int,
    lineStart: Int,
    lineEnd: Int,
) : RenderedBlock {
    /** The laid out text, without styling. */
    val text: String get() = layout.text.toString()

    override val block: Block =
        Block(layout.height + spacingAfter, lineStart, lineEnd, List(layout.lineCount) { layout.getLineBottom(it) - layout.getLineTop(it) })

    override fun draw(
        canvas: Canvas,
        from: Int,
        to: Int,
    ) {
        if (layout.lineCount == 0 || from >= to) return
        val top = layout.getLineTop(from)
        val bottom = layout.getLineBottom(to - 1)
        canvas.save()
        canvas.clipRect(0, 0, layout.width, bottom - top)
        canvas.translate(0f, -top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
}

/** A block that is drawn whole: a diagram image or a rule. */
class ImageBlock(
    private val bitmap: Bitmap,
    private val spacingAfter: Int,
    lineStart: Int,
    lineEnd: Int,
) : RenderedBlock {
    override val block: Block = Block(bitmap.height + spacingAfter, lineStart, lineEnd, emptyList())

    override fun draw(
        canvas: Canvas,
        from: Int,
        to: Int,
    ) {
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}

/**
 * [bitmap] scaled to [width] with its aspect kept (a region image fills the text column, spec 3.6), or less when that
 * would be taller than [maxHeight].
 */
fun scaleToWidth(
    bitmap: Bitmap,
    width: Int,
    maxHeight: Int,
): Bitmap {
    val scale = minOf(width.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
}

/** A thin horizontal rule. */
class RuleBlock(
    private val width: Int,
    lineStart: Int,
    lineEnd: Int,
) : RenderedBlock {
    override val block: Block = Block(RULE_HEIGHT, lineStart, lineEnd, emptyList())
    private val paint = Paint().apply { color = Color.BLACK }

    override fun draw(
        canvas: Canvas,
        from: Int,
        to: Int,
    ) {
        canvas.drawRect(0f, RULE_HEIGHT / 2f - 1f, width.toFloat(), RULE_HEIGHT / 2f + 1f, paint)
    }

    private companion object {
        const val RULE_HEIGHT = 32
    }
}

/** Builds styled text and measures it with [StaticLayout] at a fixed width. */
class TextStyles(
    private val layout: DocumentLayout,
) {
    val body: Float = DocumentLayout.BODY_TEXT_PX * layout.fontScale
    val textWidth: Int = layout.widthPx - 2 * MARGIN_PX
    val spacing: Int = (body * SPACING_EM).toInt()

    fun headingSize(level: Int): Float = body * HEADING_SCALE[(level - 1).coerceIn(0, HEADING_SCALE.lastIndex)]

    fun paint(
        sizePx: Float = body,
        mono: Boolean = false,
    ): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sizePx
            typeface = if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF
        }

    fun measure(
        text: CharSequence,
        paint: TextPaint,
    ): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .build()

    fun bold(
        s: SpannableStringBuilder,
        from: Int,
        to: Int,
    ) = s.setSpan(StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    fun italic(
        s: SpannableStringBuilder,
        from: Int,
        to: Int,
    ) = s.setSpan(StyleSpan(Typeface.ITALIC), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    fun mono(
        s: SpannableStringBuilder,
        from: Int,
        to: Int,
    ) {
        s.setSpan(TypefaceSpan("monospace"), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(BackgroundColorSpan(CODE_BACKGROUND), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Replaces every `[[colour]]` marker of [s] by its bold `[colour]` label in that ink colour. The name stays
     * readable on a greyscale panel, where the colour alone would not tell.
     */
    fun colourMarkers(s: SpannableStringBuilder) {
        var original = 0
        var removed = 0
        for (span in splitColourMarkers(s.toString())) {
            val colour = span.colour
            if (colour == null) {
                original += span.text.length
                continue
            }
            val start = original - removed
            s.replace(start, start + span.text.length + MARKER_EXTRA_BRACKETS, span.text)
            s.setSpan(ForegroundColorSpan(colour.argb), start, start + span.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            bold(s, start, start + span.text.length)
            original += span.text.length + MARKER_EXTRA_BRACKETS
            removed += MARKER_EXTRA_BRACKETS
        }
    }

    fun underline(
        s: SpannableStringBuilder,
        from: Int,
        to: Int,
    ) = s.setSpan(UnderlineSpan(), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    fun size(
        s: SpannableStringBuilder,
        from: Int,
        to: Int,
        px: Float,
    ) = s.setSpan(AbsoluteSizeSpan(px.toInt()), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    fun indent(
        s: SpannableStringBuilder,
        first: Int,
        rest: Int,
    ) = s.setSpan(LeadingMarginSpan.Standard(first, rest), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

    companion object {
        const val MARGIN_PX = 48

        /** `[[red]]` is `[red]` plus one bracket on each side. */
        private const val MARKER_EXTRA_BRACKETS = 2
        const val CODE_BACKGROUND = 0xFFE6E6E6.toInt()
        private const val SPACING_EM = 0.6f
        private const val LINE_SPACING = 1.15f
        private val HEADING_SCALE = floatArrayOf(1.7f, 1.45f, 1.25f, 1.1f, 1.0f, 1.0f)
    }
}
