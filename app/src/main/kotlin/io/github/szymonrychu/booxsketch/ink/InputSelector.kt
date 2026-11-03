package io.github.szymonrychu.booxsketch.ink

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/** User-facing input settings; [forceTouch] is the "Force touch input" switch. */
interface InputSettings {
    val forceTouch: Boolean
}

/** Sink for the reason the Onyx path was abandoned (shown on the diagnostics screen). */
fun interface OnyxDiagnostics {
    fun record(reason: String)
}

/** The pen input path in use, as the Diagnostics screen names it. */
enum class InputPath(
    val label: String,
) {
    ONYX_RAW("Onyx raw"),
    TOUCH_FALLBACK("Touch fallback"),
}

/** The selected [path] (null before the first selection) and why touch was chosen, if it was. */
data class InputStatus(
    val path: InputPath?,
    val fallbackReason: String?,
)

/** Implemented by the wrapper returned for the Onyx path so tests can inject a stylus down where raw input is expected. */
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
    private val mainExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
) {
    private val _status = MutableStateFlow(InputStatus(null, null))

    /** What the last [select] picked, updated when the Onyx path falls back at runtime. */
    val status: StateFlow<InputStatus> = _status.asStateFlow()

    private var onyxInput: OnyxWithFallback? = null

    /** True while the selected Onyx path is active with raw drawing open and enabled. */
    fun rawEnabled(): Boolean = onyxInput?.rawEnabled == true

    @Suppress("TooGenericExceptionCaught")
    fun select(): StrokeInput {
        onyxInput = null
        touchReason()?.let { return touch(it) }
        return try {
            OnyxWithFallback(onyxFactory(), touchFactory(), recording(), scope, mainExecutor).also {
                onyxInput = it
                _status.value = InputStatus(InputPath.ONYX_RAW, null)
            }
        } catch (e: RuntimeException) {
            disable(e.describe(), recording())
            touch(e.describe())
        } catch (e: LinkageError) {
            disable(e.describe(), recording())
            touch(e.describe())
        }
    }

    private fun touchReason(): String? {
        if (settings.forceTouch) return "Force touch input is on"
        if (onyxDisabled) return disabledReason ?: "Onyx input disabled"
        val probed = probe()
        return if (probed.available) null else "Onyx probe: ${probed.reason}"
    }

    private fun touch(reason: String): StrokeInput {
        _status.value = InputStatus(InputPath.TOUCH_FALLBACK, reason)
        return touchFactory()
    }

    /** [diagnostics] plus this selector's status, so a runtime fallback shows up on both. */
    private fun recording() =
        OnyxDiagnostics { reason ->
            _status.value = InputStatus(InputPath.TOUCH_FALLBACK, reason)
            diagnostics.record(reason)
        }

    companion object {
        const val RAW_CALLBACK_TIMEOUT_MS = 300L

        @Volatile
        private var onyxDisabled = false

        @Volatile
        private var disabledReason: String? = null

        internal fun disable(
            reason: String,
            diagnostics: OnyxDiagnostics,
        ) {
            disabledReason = reason
            onyxDisabled = true
            diagnostics.record(reason)
        }

        internal fun resetForTest() {
            onyxDisabled = false
            disabledReason = null
        }

        internal fun Throwable.describe(): String = "Onyx input disabled: ${this::class.java.simpleName}: $message"
    }
}

/**
 * Onyx input guarded by a runtime fallback: any exception, or a first stylus down that gets no
 * raw callback within [InputSelector.RAW_CALLBACK_TIMEOUT_MS], switches to touch for the process.
 * The stylus down is seen by a wrapped Activity window callback (it runs before any view, so it
 * works in raw mode too) and by a view touch listener; the first one wins. Only a stylus down that
 * can get a raw callback arms the watch: inside the limit rect, outside every exclude rect, while
 * raw drawing is on ([RawActivitySource.rawActive]). A pen tap on the toolbar, an overlay, or
 * during a hold must not abandon the raw path (that lost live ink in v0.3.2). An armed watch is
 * cancelled when raw drawing goes off before it fires (hold, pause, refresh, a re-open for new
 * rects) and checks [RawActivitySource.rawActive] again when it fires; the next stylus down on
 * the board arms it again. All view work of the switch runs on [mainExecutor]; the state is
 * guarded by [lock].
 */
private class OnyxWithFallback(
    private val onyx: StrokeInput,
    private val touch: StrokeInput,
    private val diagnostics: OnyxDiagnostics,
    private val scope: CoroutineScope,
    private val mainExecutor: Executor,
) : StrokeInput,
    StylusDownObserver {
    override val events: Flow<StrokeEvent> = merge(onyx.events, touch.events)

    /** Only touch has a stroke in progress to draw; raw ink is drawn by the vendor SDK. */
    override val inProgress: StateFlow<Stroke?> = touch.inProgress

    /** Raw drawing is on: no fallback, and the Onyx input reports it open and enabled. */
    val rawEnabled: Boolean
        get() = !synchronized(lock) { fellBack || stopped } && (onyx as? RawActivitySource)?.rawActive == true

    private val lock = Any()
    private var view: View? = null
    private var limit = Rect()
    private var exclude: List<Rect> = emptyList()
    private var tool = Tool.BALLPOINT
    private var width = 4f
    private var paused = false
    private var fellBack = false
    private var stopped = false
    private var confirmed = false
    private var watch: Job? = null
    private var window: Window? = null
    private var windowCallback: Window.Callback? = null
    private var originalCallback: Window.Callback? = null

    init {
        (onyx as? RawActivitySource)?.let { src ->
            src.onRawActivity = { confirm() }
            src.onFailure = { switchToTouch(with(InputSelector) { it.describe() }) }
        }
    }

    private fun confirm() =
        synchronized(lock) {
            confirmed = true
            disarm()
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        val fallen =
            synchronized(lock) {
                // New rects re-open raw drawing: a stylus down before that can get no callback.
                if (limit != this.limit || exclude != this.exclude) disarm()
                stopped = false
                this.view = view
                this.limit = limit
                this.exclude = exclude
                fellBack
            }
        if (fallen) return touch.start(view, limit, exclude)
        view.setOnTouchListener { _, ev ->
            if (isStylusDown(ev)) onStylusDownInView(ev.x, ev.y)
            false
        }
        installWindowCallback(view.context)
        guarded { onyx.start(view, limit, exclude) }
    }

    private fun isStylusDown(ev: MotionEvent) =
        ev.actionMasked == MotionEvent.ACTION_DOWN && ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

    private fun installWindowCallback(context: Context) {
        val win = context.findActivity()?.window ?: return
        // start() can run again (layout change, resume): keep exactly one wrapper installed.
        if (synchronized(lock) { windowCallback != null && win.callback === windowCallback }) return
        val original = win.callback ?: return
        val wrapper =
            object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (isStylusDown(event)) onStylusDownInWindow(event.x, event.y)
                    return original.dispatchTouchEvent(event)
                }
            }
        synchronized(lock) {
            window = win
            windowCallback = wrapper
            originalCallback = original
        }
        win.callback = wrapper
    }

    private fun restoreWindowCallback() {
        val (win, wrapper, original) = synchronized(lock) { Triple(window, windowCallback, originalCallback) }
        if (win != null && win.callback === wrapper) win.callback = original
    }

    private val inactive: Boolean get() = fellBack || stopped || confirmed

    private fun onStylusDownInWindow(
        x: Float,
        y: Float,
    ) {
        val v = synchronized(lock) { view } ?: return
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        onStylusDownInView(x - loc[0], y - loc[1])
    }

    /** [x], [y] in the pixels of the started view, the space of [limit] and [exclude]. */
    private fun onStylusDownInView(
        x: Float,
        y: Float,
    ) {
        val px = x.toInt()
        val py = y.toInt()
        val inRaw = synchronized(lock) { limit.contains(px, py) && exclude.none { it.contains(px, py) } }
        if (inRaw) onStylusDown()
    }

    private val rawOff: Boolean get() = (onyx as? RawActivitySource)?.rawActive == false

    override fun onStylusDown() {
        if (rawOff) return
        synchronized(lock) {
            if (inactive || watch != null) return
            watch =
                scope.launch {
                    delay(InputSelector.RAW_CALLBACK_TIMEOUT_MS)
                    if (rawOff) {
                        synchronized(lock) { watch = null }
                    } else {
                        switchToTouch("Onyx input disabled: no raw pen callback within ${InputSelector.RAW_CALLBACK_TIMEOUT_MS} ms")
                    }
                }
        }
    }

    /** Cancels an armed watch; call under [lock]. */
    private fun disarm() {
        watch?.cancel()
        watch = null
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) {
        val fallen =
            synchronized(lock) {
                this.tool = tool
                this.width = width
                fellBack
            }
        if (fallen) touch.setTool(tool, width) else guarded { onyx.setTool(tool, width) }
    }

    override fun pause() {
        val fallen =
            synchronized(lock) {
                paused = true
                disarm()
                fellBack
            }
        if (fallen) touch.pause() else guarded { onyx.pause() }
    }

    override fun resume() {
        val fallen =
            synchronized(lock) {
                paused = false
                fellBack
            }
        if (fallen) touch.resume() else guarded { onyx.resume() }
    }

    override fun stop() {
        val fallen =
            synchronized(lock) {
                stopped = true
                disarm()
                fellBack
            }
        restoreWindowCallback()
        if (fallen) touch.stop() else guarded { onyx.stop() }
    }

    override val freezesUi: Boolean get() = onyx.freezesUi

    override fun holdUi() {
        val fallen =
            synchronized(lock) {
                disarm()
                fellBack
            }
        if (!fallen) guarded { onyx.holdUi() }
    }

    override fun releaseUi() {
        if (!synchronized(lock) { fellBack }) guarded { onyx.releaseUi() }
    }

    /** Runs [redraw] inside the active input's refresh; a failing Onyx refresh falls back and still redraws. */
    override fun refresh(redraw: () -> Unit) {
        val fallen =
            synchronized(lock) {
                disarm()
                fellBack
            }
        if (fallen) return touch.refresh(redraw)
        var drawn = false
        guarded {
            onyx.refresh {
                drawn = true
                redraw()
            }
        }
        if (!drawn) redraw()
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

    /** Atomic transition; callable from any thread. The touch start runs on the main executor. */
    private fun switchToTouch(reason: String) {
        synchronized(lock) {
            if (fellBack || stopped) return
            fellBack = true
            disarm()
        }
        InputSelector.disable(reason, diagnostics)
        mainExecutor.execute { startTouchOnMain() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startTouchOnMain() {
        synchronized(lock) { if (stopped) return }
        try {
            onyx.stop()
        } catch (_: RuntimeException) {
            // already broken; touch takes over
        } catch (_: LinkageError) {
            // already broken; touch takes over
        }
        val (v, lim, exc) = synchronized(lock) { Triple(view, limit, exclude) }
        if (v == null) return
        val (t, w, p) = synchronized(lock) { Triple(tool, width, paused) }
        touch.start(v, lim, exc)
        touch.setTool(t, w)
        if (p) touch.pause()
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
