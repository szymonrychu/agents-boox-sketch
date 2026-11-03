package io.github.szymonrychu.booxsketch.ink

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** User-facing input settings; [forceTouch] is the "Force touch input" switch. */
interface InputSettings {
    val forceTouch: Boolean
}

/** Sink for the reason the Onyx path was abandoned (shown on the diagnostics screen). */
fun interface OnyxDiagnostics {
    fun record(reason: String)
}

/** Implemented by the wrapper returned for the Onyx path so tests can inject a stylus down. */
interface StylusDownObserver {
    fun onStylusDown()
}

/**
 * Picks the pen input: Onyx raw drawing when the probe passes, the user did not force touch and
 * no earlier failure disabled it for this process; otherwise plain touch.
 */
class InputSelector(
    private val probe: () -> ProbeResult,
    private val settings: InputSettings,
    private val diagnostics: OnyxDiagnostics,
    private val scope: CoroutineScope,
    private val onyxFactory: () -> StrokeInput,
    private val touchFactory: () -> StrokeInput,
) {
    @Suppress("TooGenericExceptionCaught")
    fun select(): StrokeInput {
        if (settings.forceTouch || onyxDisabled || !probe().available) return touchFactory()
        return try {
            OnyxWithFallback(onyxFactory(), touchFactory(), diagnostics, scope)
        } catch (e: RuntimeException) {
            disable(e.describe(), diagnostics)
            touchFactory()
        }
    }

    companion object {
        const val RAW_CALLBACK_TIMEOUT_MS = 300L

        @Volatile
        private var onyxDisabled = false

        internal fun disable(
            reason: String,
            diagnostics: OnyxDiagnostics,
        ) {
            onyxDisabled = true
            diagnostics.record(reason)
        }

        internal fun resetForTest() {
            onyxDisabled = false
        }

        internal fun Throwable.describe(): String = "Onyx input disabled: ${this::class.java.simpleName}: $message"
    }
}

/**
 * Onyx input guarded by a runtime fallback: any exception, or a first stylus down that gets no
 * raw callback within [InputSelector.RAW_CALLBACK_TIMEOUT_MS], switches to touch for the process.
 */
private class OnyxWithFallback(
    private val onyx: StrokeInput,
    private val touch: StrokeInput,
    private val diagnostics: OnyxDiagnostics,
    private val scope: CoroutineScope,
) : StrokeInput,
    StylusDownObserver {
    override val events: Flow<StrokeEvent> = merge(onyx.events, touch.events)

    private var view: View? = null
    private var limit = Rect()
    private var exclude: List<Rect> = emptyList()
    private var tool = Tool.BALLPOINT
    private var width = 4f
    private var paused = false
    private var fellBack = false
    private var confirmed = false
    private var watch: Job? = null

    init {
        (onyx as? RawActivitySource)?.let { src ->
            src.onRawActivity = { confirm() }
            src.onFailure = { switchToTouch(with(InputSelector) { it.describe() }) }
        }
    }

    @Synchronized
    private fun confirm() {
        confirmed = true
        watch?.cancel()
        watch = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        this.view = view
        this.limit = limit
        this.exclude = exclude
        if (fellBack) return touch.start(view, limit, exclude)
        view.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) onStylusDown()
            false
        }
        guarded { onyx.start(view, limit, exclude) }
    }

    @Synchronized
    override fun onStylusDown() {
        if (fellBack || confirmed || watch != null) return
        watch =
            scope.launch {
                delay(InputSelector.RAW_CALLBACK_TIMEOUT_MS)
                switchToTouch("Onyx input disabled: no raw pen callback within ${InputSelector.RAW_CALLBACK_TIMEOUT_MS} ms")
            }
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        this.tool = tool
        this.width = width
        if (fellBack) touch.setTool(tool, width) else guarded { onyx.setTool(tool, width) }
    }

    override fun pause() {
        paused = true
        if (fellBack) touch.pause() else guarded { onyx.pause() }
    }

    override fun resume() {
        paused = false
        if (fellBack) touch.resume() else guarded { onyx.resume() }
    }

    override fun stop() {
        watch?.cancel()
        if (fellBack) touch.stop() else guarded { onyx.stop() }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            switchToTouch(with(InputSelector) { e.describe() })
        } catch (e: LinkageError) {
            switchToTouch(with(InputSelector) { e.describe() })
        }
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    private fun switchToTouch(reason: String) {
        if (fellBack) return
        fellBack = true
        watch?.cancel()
        InputSelector.disable(reason, diagnostics)
        try {
            onyx.stop()
        } catch (_: RuntimeException) {
            // already broken; touch takes over
        } catch (_: LinkageError) {
            // already broken; touch takes over
        }
        val v = view ?: return
        touch.start(v, limit, exclude)
        touch.setTool(tool, width)
        if (paused) touch.pause()
    }
}
