package io.github.szymonrychu.booxsketch

import android.app.Application
import android.os.Build
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

open class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The Onyx pen SDK reflects into hidden framework APIs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        container = newContainer()
        // Decrypt the token off the main thread; the UI shows nothing until it is known.
        container.splitStoreScope.launch { container.tokenStore.load() }
    }

    /** Test hook: Robolectric tests swap the Keystore-backed key provider through a subclass. */
    protected open fun newContainer(): AppContainer = AppContainer(this)
}
