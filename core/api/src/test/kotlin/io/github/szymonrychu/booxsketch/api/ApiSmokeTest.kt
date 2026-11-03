package io.github.szymonrychu.booxsketch.api

import io.github.szymonrychu.booxsketch.model.Canvas
import org.junit.Assert.assertEquals
import org.junit.Test

// Temporary: Task 10 deletes this once real API tests exist.
class ApiSmokeTest {
    @Test
    fun describesCanvas() {
        assertEquals("100x200@300.0", ApiInfo.describe(Canvas(w = 100, h = 200, dpi = 300f)))
    }
}
