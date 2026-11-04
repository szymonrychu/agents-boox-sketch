package io.github.szymonrychu.booxsketch.onyx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnyxSdkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun on_an_onyx_device_the_res_manager_gets_the_context() {
        val seen = mutableListOf<Context>()
        OnyxSdk.init(context, available = { true }, resManagerInit = { seen += it })
        assertThat(seen).containsExactly(context)
    }

    @Test
    fun without_the_onyx_sdk_the_res_manager_is_left_alone() {
        val seen = mutableListOf<Context>()
        OnyxSdk.init(context, available = { false }, resManagerInit = { seen += it })
        assertThat(seen).isEmpty()
    }

    @Test
    fun a_failing_initialiser_never_crashes_the_app() {
        OnyxSdk.init(context, available = { true }, resManagerInit = { throw LinkageError("boom") })
    }
}
