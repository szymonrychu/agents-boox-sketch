package io.github.szymonrychu.booxsketch.documents

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Draws a diagram in some web view host and reports the bitmap (or null) through [onDone], once, on any thread. */
fun interface WebCapture {
    fun start(
        source: String,
        widthPx: Int,
        onDone: (Bitmap?) -> Unit,
    )
}

/**
 * Offline mermaid diagrams: the bundled `mermaid.min.js` runs in an offscreen web view and the result is captured to a
 * bitmap of [widthPx]. Any failure, a blank capture or a [timeoutMs] timeout gives null, which makes the paginator show
 * the diagram source as code text (that fallback is the behaviour, not an error). [render] blocks: call it off the
 * main thread.
 */
class MermaidRenderer(
    private val capture: WebCapture,
    private val timeoutMs: Long = TIMEOUT_MS,
) : BlockImage {
    constructor(context: Context) : this(WebViewCapture(context.applicationContext))

    override fun render(
        source: String,
        widthPx: Int,
    ): Bitmap? {
        val bitmap = captured(source, widthPx)
        return when {
            bitmap == null || isBlank(bitmap) -> {
                null
            }

            bitmap.width == widthPx -> {
                bitmap
            }

            else -> {
                Bitmap.createScaledBitmap(
                    bitmap,
                    widthPx,
                    (bitmap.height * widthPx.toFloat() / bitmap.width).toInt().coerceAtLeast(1),
                    true,
                )
            }
        }
    }

    /** The host's bitmap, or null on a host failure, a null answer or a timeout. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a web view failure of any kind is the text fallback
    private fun captured(
        source: String,
        widthPx: Int,
    ): Bitmap? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>()
        try {
            capture.start(source, widthPx) {
                result.set(it)
                latch.countDown()
            }
        } catch (e: Exception) {
            return null
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else null
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val first = px.firstOrNull() ?: return true
        return px.all { it == first } || px.all { it == Color.WHITE || it == Color.TRANSPARENT }
    }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val ASSET_DIR = "mermaid"

        /** sha256 of the bundled `mermaid.min.js` (mermaid 11.4.1, MIT); also recorded in MEMORY.md. */
        const val MERMAID_SHA256 = "a43bc1afd446f9c4cc66ac5dd45d02e8d65e26fc5344ec0ef787f88d6ddb6f9e"
    }
}

/**
 * The real host: a software-layer [WebView] on the main thread that loads `render.html`, runs mermaid and draws itself
 * into a bitmap. Verified on the device only (see the device checklist).
 */
class WebViewCapture(
    private val context: Context,
) : WebCapture {
    @SuppressLint("SetJavaScriptEnabled")
    override fun start(
        source: String,
        widthPx: Int,
        onDone: (Bitmap?) -> Unit,
    ) {
        Handler(Looper.getMainLooper()).post {
            val view = WebView(context)
            var finished = false

            fun finish(bitmap: Bitmap?) {
                if (finished) return
                finished = true
                view.destroy()
                onDone(bitmap)
            }
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun done(heightCss: Int) {
                        view.post { finish(draw(view, widthPx, heightCss)) }
                    }

                    @JavascriptInterface
                    fun fail(reason: String) {
                        view.post { finish(null) }
                    }
                },
                "Android",
            )
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        v: WebView,
                        url: String,
                    ) {
                        v.evaluateJavascript("renderDiagram(${JSONObject.quote(source)})", null)
                    }

                    override fun onReceivedError(
                        v: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) finish(null)
                    }
                }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(MIN_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, widthPx, MIN_HEIGHT)
            view.loadUrl("file:///android_asset/${MermaidRenderer.ASSET_DIR}/render.html")
        }
    }

    private fun draw(
        view: WebView,
        widthPx: Int,
        heightCss: Int,
    ): Bitmap? {
        val heightPx = (heightCss * context.resources.displayMetrics.density).toInt().coerceIn(1, MAX_HEIGHT)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        const val MIN_HEIGHT = 100
        const val MAX_HEIGHT = 8_000
    }
}
