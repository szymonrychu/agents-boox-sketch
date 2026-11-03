package io.github.szymonrychu.booxsketch.model

import org.junit.Assert.assertEquals
import org.junit.Test

// Temporary: Task 7 deletes this once real model tests exist.
class ModelSmokeTest {
    @Test
    fun canvasKeepsItsSize() {
        assertEquals(1404, Canvas(w = 1404, h = 1872, dpi = 300).w)
    }
}
