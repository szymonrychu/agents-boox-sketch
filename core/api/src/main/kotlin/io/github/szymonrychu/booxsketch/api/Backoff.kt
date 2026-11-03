package io.github.szymonrychu.booxsketch.api

import kotlin.random.Random
import kotlin.time.Duration

/** The only retry backoff in the app: exponential x2 from [base], capped at [cap], plus 0..[jitter] fraction. */
class Backoff(
    private val base: Duration,
    private val cap: Duration,
    private val jitter: Double,
) {
    /** Delay before retry number [attempt] (0 = first retry). */
    fun delayFor(
        attempt: Int,
        random: Random = Random.Default,
    ): Duration {
        val exp = attempt.coerceIn(0, MAX_DOUBLINGS)
        val raw = base * Math.pow(2.0, exp.toDouble())
        val capped = if (raw > cap) cap else raw
        return capped * (1.0 + random.nextDouble() * jitter)
    }

    private companion object {
        const val MAX_DOUBLINGS = 30
    }
}
