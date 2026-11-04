package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Why a diagram is shown as code text; [caption] is the one visible line under the code. */
sealed class DiagramFailure(
    detail: String,
) {
    val caption: String = "diagram failed: " + detail

    data class Timeout(
        val ms: Long,
    ) : DiagramFailure("timed out after ${ms / MS_PER_S} s")

    data object Blank : DiagramFailure("blank capture")

    data class Parse(
        val message: String,
    ) : DiagramFailure("mermaid parse error: " + oneLine(message))

    data class WebViewUnavailable(
        val message: String,
    ) : DiagramFailure("web view unavailable: " + oneLine(message))

    data object Budget : DiagramFailure("skipped, document diagram time used up")

    data object NoImage : DiagramFailure("renderer returned no image")

    data class Crashed(
        val message: String,
    ) : DiagramFailure("renderer error: " + oneLine(message))

    companion object {
        const val TAG = "BooxMermaid"
        private const val MS_PER_S = 1_000L
        private const val MAX_DETAIL = 100

        /** First line, ASCII only, capped: the caption must stay one line. */
        fun oneLine(message: String): String {
            val line = message.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "unknown"
            val ascii = line.map { if (it.code in ' '.code..'~'.code) it else '?' }.joinToString("")
            return if (ascii.length > MAX_DETAIL) ascii.take(MAX_DETAIL - 3) + "..." else ascii
        }
    }
}

/** What a capture host answers. */
sealed interface CaptureResult {
    class Success(
        val bitmap: Bitmap,
    ) : CaptureResult

    class Failed(
        val failure: DiagramFailure,
    ) : CaptureResult
}

/** The outcome of one diagram render. */
sealed interface DiagramResult {
    class Image(
        val bitmap: Bitmap,
    ) : DiagramResult

    class Failed(
        val failure: DiagramFailure,
    ) : DiagramResult
}

/** A [BlockImage] that can also say why it produced no image. */
interface DiagramRenderer : BlockImage {
    fun renderDiagram(
        source: String,
        widthPx: Int,
    ): DiagramResult

    override fun render(
        source: String,
        widthPx: Int,
    ): Bitmap? = (renderDiagram(source, widthPx) as? DiagramResult.Image)?.bitmap
}

/** Draws a diagram in some web view host and reports the result through [onDone], once, on any thread. */
fun interface WebCapture {
    fun start(
        source: String,
        widthPx: Int,
        onDone: (CaptureResult) -> Unit,
    )
}

/**
 * Offline mermaid diagrams: the bundled `mermaid.min.js` runs in an offscreen web view and the result is captured to a
 * bitmap of [widthPx]. A blank capture is retried once; any other failure gives a [DiagramFailure], which makes the
 * paginator show the diagram source as code text plus a caption naming the reason, and logs it under
 * [DiagramFailure.TAG]. Nothing is cached here, so every call retries. [renderDiagram] blocks: call it off the main thread.
 */
class MermaidRenderer(
    private val capture: WebCapture,
    private val timeoutMs: Long = TIMEOUT_MS,
) : DiagramRenderer {
    constructor(context: Context) : this(WebViewCapture(context.applicationContext, TIMEOUT_MS), TIMEOUT_MS)

    override fun renderDiagram(
        source: String,
        widthPx: Int,
    ): DiagramResult {
        var result = attempt(source, widthPx)
        if (result is DiagramResult.Failed && result.failure == DiagramFailure.Blank) {
            Log.w(DiagramFailure.TAG, "blank capture, retrying once")
            result = attempt(source, widthPx)
        }
        if (result is DiagramResult.Failed) Log.w(DiagramFailure.TAG, result.failure.caption)
        return result
    }

    private fun attempt(
        source: String,
        widthPx: Int,
    ): DiagramResult =
        when (val captured = captured(source, widthPx)) {
            is CaptureResult.Failed -> DiagramResult.Failed(captured.failure)
            is CaptureResult.Success -> scaled(captured.bitmap, widthPx)
        }

    private fun scaled(
        bitmap: Bitmap,
        widthPx: Int,
    ): DiagramResult =
        when {
            isBlank(bitmap) -> {
                DiagramResult.Failed(DiagramFailure.Blank)
            }

            bitmap.width == widthPx -> {
                DiagramResult.Image(bitmap)
            }

            else -> {
                DiagramResult.Image(
                    Bitmap.createScaledBitmap(
                        bitmap,
                        widthPx,
                        (bitmap.height * widthPx.toFloat() / bitmap.width).toInt().coerceAtLeast(1),
                        true,
                    ),
                )
            }
        }

    /** The host's answer, a web view failure when the host throws, or a timeout. */
    @Suppress("TooGenericExceptionCaught") // a web view failure of any kind is reported, never thrown
    private fun captured(
        source: String,
        widthPx: Int,
    ): CaptureResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<CaptureResult>()
        try {
            capture.start(source, widthPx) {
                result.set(it)
                latch.countDown()
            }
        } catch (e: Exception) {
            return CaptureResult.Failed(DiagramFailure.WebViewUnavailable(e.message ?: e.javaClass.simpleName))
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            result.get()
        } else {
            CaptureResult.Failed(DiagramFailure.Timeout(timeoutMs))
        }
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val first = px.firstOrNull() ?: return true
        return px.all { it == first } || px.all { it == Color.WHITE || it == Color.TRANSPARENT }
    }

    companion object {
        /** Per attempt. See MEMORY.md 2026-11-04 for why 20 s. */
        const val TIMEOUT_MS = 20_000L

        /** Total diagram time per document (see [MarkdownPaginator]); later diagrams become captioned code. */
        const val DOCUMENT_BUDGET_MS = 60_000L
        const val ASSET_DIR = "mermaid"

        /** sha256 of the bundled `mermaid.min.js` (mermaid 11.4.1, MIT); also recorded in MEMORY.md. */
        const val MERMAID_SHA256 = "a43bc1afd446f9c4cc66ac5dd45d02e8d65e26fc5344ec0ef787f88d6ddb6f9e"
    }
}
