package io.github.szymonrychu.booxsketch.ink

/**
 * Optional hooks of a raw pen input, used by the app to watch the Onyx path: [onRawActivity]
 * fires when the vendor SDK delivers any raw pen callback, [onFailure] when the SDK path throws.
 */
interface RawActivitySource {
    var onRawActivity: (() -> Unit)?
    var onFailure: ((Throwable) -> Unit)?
}
