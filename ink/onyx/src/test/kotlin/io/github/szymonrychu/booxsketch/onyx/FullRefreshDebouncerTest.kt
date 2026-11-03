package io.github.szymonrychu.booxsketch.onyx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FullRefreshDebouncerTest {
    private val pending = mutableListOf<Pair<Runnable, Long>>()
    private var fired = 0
    private val debouncer =
        FullRefreshDebouncer(
            delayMs = 250L,
            post = { r, d -> pending += r to d },
            cancel = { r -> pending.removeAll { it.first === r } },
            action = { fired++ },
        )

    @Test
    fun bursts_of_requests_collapse_into_one_delayed_refresh() {
        debouncer.request()
        debouncer.request()
        debouncer.request()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().second).isEqualTo(250L)
        pending.single().first.run()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun cancel_drops_a_pending_refresh() {
        debouncer.request()
        debouncer.cancel()
        assertThat(pending).isEmpty()
        assertThat(fired).isEqualTo(0)
    }
}
