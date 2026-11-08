package io.github.szymonrychu.booxsketch.ink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.onyx.InkExperiments
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InkExperimentPrefsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun unset_toggles_take_the_single_default() {
        val prefs = InkExperimentPrefs(ctx)
        assertThat(prefs.current()).isEqualTo(InkExperiments())
        assertThat(prefs.toggles.value).isEqualTo(InkExperiments())
    }

    @Test
    fun a_toggle_set_through_one_instance_persists_and_reaches_another() {
        val ui = InkExperimentPrefs(ctx)
        val pen = InkExperimentPrefs(ctx)
        val flipped = InkExperiments(vendorResumeOrder = !InkExperiments.DEFAULT_ON, regionPenConfig = InkExperiments.DEFAULT_ON)
        ui.set(flipped)
        assertThat(ui.toggles.value).isEqualTo(flipped)
        assertThat(pen.current()).isEqualTo(flipped)
        assertThat(InkExperimentPrefs(ctx).toggles.value).isEqualTo(flipped)
    }
}
