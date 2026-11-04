package io.github.szymonrychu.booxsketch

import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import io.github.szymonrychu.booxsketch.pairing.FakeKeyProvider
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking

/**
 * A paired app whose pen input is a [FreezingInput] (behind the real Onyx fallback wrapper): tests
 * read its lifecycle log and commit strokes through [strokes]. Fresh per container (per test).
 */
class FreezingInputApp : App() {
    override fun newContainer(): AppContainer {
        InputSelector.resetForTest()
        strokes = Channel(Channel.UNLIMITED)
        input = FreezingInput(strokes.receiveAsFlow())
        return AppContainer(
            this,
            keyProvider = FakeKeyProvider(),
            probe = { ProbeResult(true, "test") },
            onyxFactory = { input },
        ).also { runBlocking { it.tokenStore.save("https://connector.example", "test-token") } }
    }

    internal companion object {
        lateinit var strokes: Channel<StrokeEvent>
        lateinit var input: FreezingInput
    }
}
