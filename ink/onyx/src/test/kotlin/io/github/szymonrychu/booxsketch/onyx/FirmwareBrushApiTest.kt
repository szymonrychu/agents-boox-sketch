package io.github.szymonrychu.booxsketch.onyx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Method

/** Shaped like the SDK's SDMDevice: the firmware methods are looked up once into static fields. */
@Suppress("UtilityClassWithPublicConstructor") // instantiated like the SDK device class
private open class LookedUpDevice {
    companion object {
        @JvmField var sMethodSetStrokeStyle: Method? = Any::class.java.getMethod("hashCode")

        @JvmField var sMethodSetStrokeColor: Method? = Any::class.java.getMethod("hashCode")
    }
}

/** The lookup failed for the colour (hidden API refused, or the firmware lacks the method). */
@Suppress("UtilityClassWithPublicConstructor") // instantiated like the SDK device class
private class ColourLookupFailedDevice {
    companion object {
        @JvmField var sMethodSetStrokeStyle: Method? = Any::class.java.getMethod("hashCode")

        @JvmField var sMethodSetStrokeColor: Method? = null
    }
}

/** A subclass inherits the fields of its device base class. */
private class SubDevice : LookedUpDevice()

class FirmwareBrushApiTest {
    @Test
    fun both_firmware_methods_found_is_ok() {
        assertThat(FirmwareBrushApi.status(LookedUpDevice())).isEqualTo("ok (LookedUpDevice)")
        assertThat(FirmwareBrushApi.status(SubDevice())).isEqualTo("ok (SubDevice)")
    }

    @Test
    fun a_null_method_is_reported_missing_by_name() {
        assertThat(FirmwareBrushApi.status(ColourLookupFailedDevice()))
            .isEqualTo("missing (ColourLookupFailedDevice: setStrokeColor)")
    }

    @Test
    fun a_device_without_the_fields_or_no_device_is_unknown_or_missing() {
        assertThat(FirmwareBrushApi.status(Any())).isEqualTo("unknown (Object: no stroke method fields)")
        assertThat(FirmwareBrushApi.status(null)).isEqualTo("missing (no device)")
    }
}
