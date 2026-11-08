package io.github.szymonrychu.booxsketch.ink

import android.content.Context
import android.content.SharedPreferences
import io.github.szymonrychu.booxsketch.onyx.InkExperiments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Diagnostics tab's live ink toggles ([InkExperiments]), persisted across launches. Unset toggles take the
 * [InkExperiments] defaults. The pen input reads [current] at every open, enable and brush change; it reads the
 * preferences themselves, so every instance over the same file sees a [set] made through another.
 */
class InkExperimentPrefs(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(NAME, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())

    val toggles: StateFlow<InkExperiments> = state.asStateFlow()

    fun current(): InkExperiments = read()

    private val listeners = mutableListOf<() -> Unit>()

    /** Runs [listener] after every [set], once the new toggles are readable through [current]. */
    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    fun set(toggles: InkExperiments) {
        prefs
            .edit()
            .putBoolean(VENDOR_RESUME_ORDER, toggles.vendorResumeOrder)
            .putBoolean(REGION_PEN_CONFIG, toggles.regionPenConfig)
            .apply()
        state.value = toggles
        listeners.forEach { it() }
    }

    private fun read(): InkExperiments {
        val defaults = InkExperiments()
        return InkExperiments(
            vendorResumeOrder = prefs.getBoolean(VENDOR_RESUME_ORDER, defaults.vendorResumeOrder),
            regionPenConfig = prefs.getBoolean(REGION_PEN_CONFIG, defaults.regionPenConfig),
        )
    }

    private companion object {
        const val NAME = "ink_experiments"
        const val VENDOR_RESUME_ORDER = "vendor_resume_order"
        const val REGION_PEN_CONFIG = "region_pen_config"
    }
}
