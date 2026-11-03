package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    private val noJitter = Backoff(1.seconds, 300.seconds, 0.0)

    @Test
    fun doubles_from_base() {
        assertThat(noJitter.delayFor(0)).isEqualTo(1.seconds)
        assertThat(noJitter.delayFor(1)).isEqualTo(2.seconds)
        assertThat(noJitter.delayFor(4)).isEqualTo(16.seconds)
    }

    @Test
    fun backoff_caps_at_300s() {
        assertThat(noJitter.delayFor(9)).isEqualTo(300.seconds)
        assertThat(noJitter.delayFor(1000)).isEqualTo(300.seconds)
    }

    @Test
    fun backoff_jitter_within_fraction() {
        val b = Backoff(1.seconds, 300.seconds, 0.2)
        val random = Random(42)
        repeat(200) {
            val d = b.delayFor(3, random)
            assertThat(d).isAtLeast(8.seconds)
            assertThat(d).isAtMost(9.6.seconds)
        }
    }
}
