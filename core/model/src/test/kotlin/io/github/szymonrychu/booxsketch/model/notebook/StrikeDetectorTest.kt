package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.g
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.line
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.pen
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.textY
import org.junit.Test

class StrikeDetectorTest {
    private val row = 4
    private val band = g.textBandTop(row).toFloat()..g.rowLineY(row).toFloat()

    @Test
    fun flat_wide_stroke_is_strike() {
        assertThat(StrikeDetector.isStrike(line("s", 100f, 300f, textY(row)), band)).isTrue()
        // A slight slope is still flat: 8 px over 200 px.
        assertThat(StrikeDetector.isStrike(pen("t", 100f, textY(row) - 4f, 200f, textY(row), 300f, textY(row) + 4f), band)).isTrue()
    }

    @Test
    fun tall_stroke_is_not() {
        // 40 px tall: over 40 percent of the 53 px band.
        assertThat(StrikeDetector.isStrike(pen("s", 100f, textY(row) - 20f, 200f, textY(row) + 20f), band)).isFalse()
        // Flat but in the annotation band, not the text band.
        assertThat(StrikeDetector.isStrike(line("u", 100f, 300f, g.rowTop(row) + 20f), band)).isFalse()
        // Narrow: not wider than 1.5 x its height.
        assertThat(StrikeDetector.isStrike(pen("v", 100f, textY(row) - 9f, 125f, textY(row) + 9f), band)).isFalse()
    }

    @Test
    fun curvy_stroke_is_not() {
        // Out to the right and back: far from its own chord, though only 20 px tall.
        val y = textY(row) - 10f
        val hook = pen("s", 100f, y, 200f, y + 5f, 300f, y + 10f, 250f, y + 15f, 150f, y + 18f, 110f, y + 20f)
        assertThat(StrikeDetector.isStrike(hook, band)).isFalse()
    }

    @Test
    fun scribble_inside_band_is_strike() {
        val top = g.textBandTop(row) + 10f
        val bottom = g.textBandTop(row) + 42f
        val zigzag = pen("s", 100f, top, 120f, bottom, 140f, top, 160f, bottom, 180f, top, 200f, bottom, 220f, top)
        assertThat(StrikeDetector.isStrike(zigzag, band)).isTrue()
        // The same zig-zag reaching well into the annotation band is not.
        val tall = pen("t", 100f, top - 40f, 120f, bottom, 140f, top - 40f, 160f, bottom, 180f, top - 40f, 200f, bottom)
        assertThat(StrikeDetector.isStrike(tall, band)).isFalse()
    }
}
