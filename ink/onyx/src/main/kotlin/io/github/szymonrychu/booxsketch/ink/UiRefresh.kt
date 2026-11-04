package io.github.szymonrychu.booxsketch.ink

/**
 * Told by the toolbar (and other UI outside the board) that its state changed, so the change
 * reaches an e-ink panel frozen by raw drawing. [settled] marks the end of a burst (slider
 * release); otherwise calls may be throttled. The pen path never calls it.
 */
interface UiRefresh {
    fun uiChanged(settled: Boolean = true)

    /** A show or hide of chrome: a settled change that ends with a full refresh. */
    fun chromeChanged() = uiChanged()

    /** For inputs that do not freeze the screen. */
    object None : UiRefresh {
        override fun uiChanged(settled: Boolean) = Unit
    }
}
