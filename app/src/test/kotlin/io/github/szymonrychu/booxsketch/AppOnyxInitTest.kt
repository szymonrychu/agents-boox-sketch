package io.github.szymonrychu.booxsketch

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.pairing.FakeKeyProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class OnyxInitApp : App() {
    var initCalls = 0
    var containerExistedAtInit = false

    override fun newContainer(): AppContainer = AppContainer(this, keyProvider = FakeKeyProvider())

    override fun initOnyxSdk() {
        initCalls++
        containerExistedAtInit = runCatching { container }.isSuccess
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = OnyxInitApp::class, sdk = [35])
class AppOnyxInitTest {
    @Test
    fun onCreate_initialises_the_onyx_sdk_once_before_the_container_exists() {
        val app = ApplicationProvider.getApplicationContext<OnyxInitApp>()
        assertThat(app.initCalls).isEqualTo(1)
        assertThat(app.containerExistedAtInit).isFalse()
        Robolectric.buildActivity(MainActivity::class.java).setup()
        assertThat(app.initCalls).isEqualTo(1)
    }
}
