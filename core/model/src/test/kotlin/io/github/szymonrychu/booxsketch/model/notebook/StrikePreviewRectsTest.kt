package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.RectF
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.annotationY
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.g
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.line
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.measure
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.page
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.run
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.textY
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.word
import org.junit.Test

class StrikePreviewRectsTest {
    private val milk = run("L1", 4, "buy fresh milk")

    @Test
    fun struck_words_get_their_text_band_rect() {
        val rects = StrikePreview.rects(page(listOf(milk), line("s", 140f, 340f, textY(4))), g, measure)
        assertThat(rects).containsExactly(RectF(133f, g.textBandTop(4).toFloat(), 333f, g.rowLineY(4).toFloat()))
    }

    @Test
    fun no_strike_no_rect() {
        val p = page(listOf(milk), word("w", 140f, 200f, annotationY(4)))
        assertThat(StrikePreview.rects(p, g, measure)).isEmpty()
        val consumed = page(listOf(milk), line("s", 140f, 340f, textY(4))).copy(consumed = setOf("s"))
        assertThat(StrikePreview.rects(consumed, g, measure)).isEmpty()
    }
}
