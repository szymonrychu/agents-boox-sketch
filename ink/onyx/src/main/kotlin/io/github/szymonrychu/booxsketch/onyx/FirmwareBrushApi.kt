package io.github.szymonrychu.booxsketch.onyx

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Whether the live ink style and colour can reach the firmware. The SDK's device class (SDMDevice,
 * RK33XXDevice, ...) looks up `android.onyx.ViewUpdateHelper.setStrokeStyle/setStrokeColor` once into
 * static fields and later calls them through `ReflectUtil.invokeMethodSafely`, which swallows every
 * error; a null field means every `setStrokeStyle`/`setStrokeColor` is silently dropped and the live ink
 * uses the firmware's global pen state. Width also goes through the native reader, so it would still work.
 */
object FirmwareBrushApi {
    private val methods = listOf("setStrokeStyle", "setStrokeColor")

    /** "ok (<device class>)", "missing (<device class>: <methods>)", "unknown (...)" or "n/a (...)" off an Onyx device. */
    fun status(): String {
        val probe = OnyxProbe.available()
        val result = if (probe.available) status(Epd.device()) else "n/a (${probe.reason})"
        InkDiag.info("firmware brush API: $result")
        return result
    }

    /** The status of [device], an instance of the SDK's device class, or null when it could not be created. */
    fun status(device: Any?): String {
        if (device == null) return "missing (no device)"
        val name = device.javaClass.simpleName
        val fields = methods.associateWith { staticField(device.javaClass, "sMethod" + it.replaceFirstChar(Char::uppercaseChar)) }
        if (fields.values.all { it == null }) return "unknown ($name: no stroke method fields)"
        val missing = fields.filter { (_, f) -> f == null || read(f) == null }.keys
        return if (missing.isEmpty()) "ok ($name)" else "missing ($name: ${missing.joinToString()})"
    }

    private fun staticField(
        type: Class<*>,
        name: String,
    ): Field? {
        var c: Class<*>? = type
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }?.let { return it }
            c = c.superclass
        }
        return null
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun read(f: Field): Any? =
        try {
            f.isAccessible = true
            f.get(null)
        } catch (e: RuntimeException) {
            null
        } catch (e: IllegalAccessException) {
            null
        }
}
