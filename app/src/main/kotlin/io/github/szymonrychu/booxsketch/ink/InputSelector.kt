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
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    private val mainExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
) {
    @Suppress("TooGenericExceptionCaught")
    fun select(): StrokeInput {
        if (settings.forceTouch || onyxDisabled || !probe().available) return touchFactory()
        return try {
            OnyxWithFallback(onyxFactory(), touchFactory(), diagnostics, scope, mainExecutor)
        } catch (e: RuntimeException) {
            disable(e.describe(), diagnostics)
            touchFactory()
        } catch (e: LinkageError) {
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
 * The stylus down is seen by a wrapped Activity window callback (it runs before any view, so it
 * works in raw mode too) and by a view touch listener; the first one wins. All view work of the
 * switch runs on [mainExecutor]; the state is guarded by [lock].
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
            watch?.cancel()
            watch = null
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        val fallen =
            synchronized(lock) {
                stopped = false
                this.view = view
                this.limit = limit
                this.exclude = exclude
                fellBack
            }
        if (fallen) return touch.start(view, limit, exclude)
        view.setOnTouchListener { _, ev ->
            if (isStylusDown(ev)) onStylusDown()
            false
        }
        installWindowCallback(view.context)
        guarded { onyx.start(view, limit, exclude) }
    }

    private fun isStylusDown(ev: MotionEvent) =
        ev.actionMasked == MotionEvent.ACTION_DOWN && ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

    private fun installWindowCallback(context: Context) {
        val win = context.findActivity()?.window ?: return
        val original = win.callback ?: return
        val wrapper =
            object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (isStylusDown(event)) onStylusDown()
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

    private fun inactive() = fellBack || stopped || confirmed

    override fun onStylusDown() {
        synchronized(lock) {
            if (inactive() || watch != null) return
            watch =
                scope.launch {
                    delay(InputSelector.RAW_CALLBACK_TIMEOUT_MS)
                    switchToTouch("Onyx input disabled: no raw pen callback within ${InputSelector.RAW_CALLBACK_TIMEOUT_MS} ms")
                }
        }
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
                watch?.cancel()
                watch = null
                fellBack
            }
        restoreWindowCallback()
        if (fallen) touch.stop() else guarded { onyx.stop() }
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
            watch?.cancel()
            watch = null
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
