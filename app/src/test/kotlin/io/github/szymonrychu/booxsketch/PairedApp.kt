package io.github.szymonrychu.booxsketch

import io.github.szymonrychu.booxsketch.pairing.FakeKeyProvider
import kotlinx.coroutines.runBlocking

/** Robolectric application of an already paired device: Robolectric has no Android Keystore. */
class PairedApp : App() {
    override fun newContainer(): AppContainer =
        AppContainer(this, keyProvider = FakeKeyProvider()).also {
            runBlocking { it.tokenStore.save("https://connector.example", "test-token") }
        }
}
