package io.github.szymonrychu.booxsketch.onyx

import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.device.Device
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Whether the live ink style and colour can reach the firmware. The SDK's device class (SDMDevice,
 * RK33XXDevice, ...) looks up `android.onyx.ViewUpdateHelper.setStrokeStyle/setStrokeColor` once into
 * static fields and later calls them through `ReflectUtil.invokeMethodSafely`, which swallows every
 * error; a null field means every `setStrokeStyle`/`setStrokeColor` is silently dropped and the live ink
 * uses the firmware's global pen state. Width also goes through the native reader, so it would still work.
 */
@Suppress("TooManyFunctions") // the status line, the dump and their reflection helpers
object FirmwareBrushApi {
    private val methods = listOf("setStrokeStyle", "setStrokeColor")

    /** The device class's firmware method fields the live ink depends on, in dump order. */
    private val dumpFields =
        listOf(
            "sMethodSetStrokeStyle",
            "sMethodSetStrokeColor",
            "sMethodSetStrokeWidth",
            "sMethodSetStrokeParameters",
            "sMethodSetScreenHandWritingRegionConfig",
            "sMethodClearScreenHandWritingRegionConfig",
            "sPenButtonClass",
        )

    private const val VIEW_UPDATE_HELPER = "android.onyx.ViewUpdateHelper"
    private const val PEN_BUTTON = "android.onyx.handwriting.PenButton"

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

    /**
     * The firmware pen API by reflection, one fact per line, also logged under [InkDiag]: the device class, colour
     * type and pen state, every declared method of `android.onyx.ViewUpdateHelper`, the `PenButton` constants and
     * the set/null/absent state of the device class's firmware method fields. "n/a (...)" off an Onyx device.
     */
    fun dump(): String {
        val probe = OnyxProbe.available()
        val result =
            if (!probe.available) {
                "n/a (${probe.reason})"
            } else {
                val device = Epd.device()
                dump(
                    helper = classOrNull(VIEW_UPDATE_HELPER),
                    penButton = penButtonClass(device) ?: classOrNull(PEN_BUTTON),
                    device = device,
                    colorType = attempt { Device.currentDevice().colorType },
                    penState = attempt { EpdController.getPenState() },
                )
            }
        result.lines().forEach { InkDiag.info("firmware API: $it") }
        return result
    }

    /** [dump] of the given classes and values; null is a class or value that could not be read. */
    fun dump(
        helper: Class<*>?,
        penButton: Class<*>?,
        device: Any?,
        colorType: Int?,
        penState: Int?,
    ): String {
        val methods =
            helper?.let { h ->
                "ViewUpdateHelper methods: " +
                    attempt {
                        h.declaredMethods
                            .filterNot { it.isSynthetic }
                            .map { it.name }
                            .distinct()
                            .sorted()
                            .joinToString()
                    }
            }
                ?: "ViewUpdateHelper: absent"
        val buttons = penButton?.let { b -> "PenButton: " + penButtonValues(b).joinToString { (n, v) -> "$n=$v" } } ?: "PenButton: absent"
        val fields =
            if (device == null) {
                "sMethod fields: no device"
            } else {
                dumpFields.joinToString(" ") { name ->
                    val f = staticField(device.javaClass, name)
                    "$name=" +
                        if (f == null) {
                            "absent"
                        } else if (read(f) == null) {
                            "null"
                        } else {
                            "set"
                        }
                }
            }
        val name = device?.javaClass?.simpleName ?: "none"
        return listOf(
            "Device: $name colorType=${colorType ?: "?"} penState=${penState ?: "?"}",
            methods,
            buttons,
            fields,
        ).joinToString("\n")
    }

    /** The firmware pen button names (`PenButton` static int fields, by value), for the region pen config. */
    fun penButtons(): List<String> = penButtons(penButtonClass(Epd.device()) ?: classOrNull(PEN_BUTTON))

    fun penButtons(type: Class<*>?): List<String> = type?.let { penButtonValues(it).map { (n, _) -> n } } ?: emptyList()

    /** The `PenButton` class the SDK's device class looked up into its `sPenButtonClass` field, if any. */
    fun penButtonClass(device: Any?): Class<*>? {
        val f = device?.let { staticField(it.javaClass, "sPenButtonClass") } ?: return null
        return read(f) as? Class<*>
    }

    private fun penButtonValues(type: Class<*>): List<Pair<String, Int>> =
        attempt {
            type.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                .mapNotNull { f -> (read(f) as? Int)?.let { f.name to it } }
                .sortedWith(compareBy({ it.second }, { it.first }))
        } ?: emptyList()

    private fun classOrNull(name: String): Class<*>? = attempt { Class.forName(name) }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun <T> attempt(block: () -> T): T? =
        try {
            block()
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: LinkageError) {
            null
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
