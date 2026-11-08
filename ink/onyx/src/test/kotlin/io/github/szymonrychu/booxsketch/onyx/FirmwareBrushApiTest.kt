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

/** Shaped like `android.onyx.ViewUpdateHelper`: static firmware methods. */
@Suppress("unused", "UtilityClassWithPublicConstructor")
private class FakeViewUpdateHelper {
    companion object {
        @JvmStatic fun setStrokeStyle(style: Int) = Unit

        @JvmStatic fun setScreenHandWritingRegionConfig() = Unit
    }
}

/** Shaped like `android.onyx.handwriting.PenButton`: static int constants per pen button. */
@Suppress("unused", "UtilityClassWithPublicConstructor")
private class FakePenButton {
    companion object {
        const val SIDE_BUTTON = 1

        const val TIP = 0

        const val LABEL = "not an int"
    }
}

/** A device whose region config lookup failed and that has no clear method field at all. */
@Suppress("UtilityClassWithPublicConstructor") // instantiated like the SDK device class
private class RegionDevice {
    companion object {
        @JvmField var sMethodSetStrokeStyle: Method? = Any::class.java.getMethod("hashCode")

        @JvmField var sMethodSetStrokeColor: Method? = Any::class.java.getMethod("hashCode")

        @JvmField var sMethodSetScreenHandWritingRegionConfig: Method? = null

        @JvmField var sPenButtonClass: Class<*>? = FakePenButton::class.java
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

    @Test
    fun pen_buttons_are_the_static_int_fields_in_value_order() {
        assertThat(FirmwareBrushApi.penButtons(FakePenButton::class.java)).containsExactly("TIP", "SIDE_BUTTON").inOrder()
        assertThat(FirmwareBrushApi.penButtons(null)).isEmpty()
    }

    @Test
    fun the_pen_button_class_comes_from_the_device_field() {
        assertThat(FirmwareBrushApi.penButtonClass(RegionDevice())).isEqualTo(FakePenButton::class.java)
        assertThat(FirmwareBrushApi.penButtonClass(Any())).isNull()
    }

    @Test
    fun the_dump_lists_methods_pen_buttons_fields_and_device_state() {
        val dump =
            FirmwareBrushApi.dump(
                helper = FakeViewUpdateHelper::class.java,
                penButton = FakePenButton::class.java,
                device = RegionDevice(),
                colorType = 1,
                penState = 2,
            )
        assertThat(dump.lines())
            .containsExactly(
                "Device: RegionDevice colorType=1 penState=2",
                "ViewUpdateHelper methods: setScreenHandWritingRegionConfig, setStrokeStyle",
                "PenButton: TIP=0, SIDE_BUTTON=1",
                "sMethodSetStrokeStyle=set sMethodSetStrokeColor=set sMethodSetStrokeWidth=absent " +
                    "sMethodSetStrokeParameters=absent sMethodSetScreenHandWritingRegionConfig=null " +
                    "sMethodClearScreenHandWritingRegionConfig=absent sPenButtonClass=set",
            ).inOrder()
    }

    @Test
    fun a_field_read_that_throws_a_linkage_error_is_reported_null_not_thrown() {
        FirmwareBrushApi.staticGetter = { throw ExceptionInInitializerError("boom") }
        try {
            val dump = FirmwareBrushApi.dump(null, FakePenButton::class.java, RegionDevice(), colorType = 1, penState = 2)
            assertThat(dump).contains("sMethodSetStrokeStyle=null")
            assertThat(dump).contains("PenButton: \n")
            assertThat(FirmwareBrushApi.status(LookedUpDevice())).isEqualTo("missing (LookedUpDevice: setStrokeStyle, setStrokeColor)")
        } finally {
            FirmwareBrushApi.staticGetter = { it.get(null) }
        }
    }

    @Test
    fun the_dump_names_what_is_absent() {
        val dump = FirmwareBrushApi.dump(helper = null, penButton = null, device = null, colorType = null, penState = null)
        assertThat(dump.lines())
            .containsExactly(
                "Device: none colorType=? penState=?",
                "ViewUpdateHelper: absent",
                "PenButton: absent",
                "sMethod fields: no device",
            ).inOrder()
    }
}
