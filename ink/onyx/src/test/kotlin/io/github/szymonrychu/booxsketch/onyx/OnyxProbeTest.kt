package io.github.szymonrychu.booxsketch.onyx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnyxProbeTest {
    @Test
    fun onyx_brand_with_sdk_passes() {
        assertThat(OnyxProbe.evaluate("onyx", "Other", sdkPresent = true).available).isTrue()
        assertThat(OnyxProbe.evaluate("x", "ONYX", sdkPresent = true).available).isTrue()
    }

    @Test
    fun other_brand_or_missing_sdk_fails_with_a_reason() {
        assertThat(OnyxProbe.evaluate("google", "Google", sdkPresent = true).available).isFalse()
        val noSdk = OnyxProbe.evaluate("ONYX", "ONYX", sdkPresent = false)
        assertThat(noSdk.available).isFalse()
        assertThat(noSdk.reason).isNotEmpty()
    }
}
