package io.github.szymonrychu.booxsketch

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The Onyx pen SDK reflects into hidden framework APIs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        container = AppContainer(this)
    }
}
