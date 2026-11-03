package io.github.szymonrychu.booxsketch.ink

/**
 * Optional hooks of a raw pen input, used by the app to watch the Onyx path: [onRawActivity]
 * fires when the vendor SDK delivers any raw pen callback, [onFailure] when the SDK path throws.
 * [rawActive] is true only while raw drawing is open and enabled, i.e. while a stylus down inside
 * its limit rect can get a raw callback at all; a source that does not track it reports true.
 */
interface RawActivitySource {
    var onRawActivity: (() -> Unit)?
    var onFailure: ((Throwable) -> Unit)?
    val rawActive: Boolean get() = true
}
