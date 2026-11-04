package io.github.szymonrychu.booxsketch.documents

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * The real host: a software-layer [WebView] on the main thread that loads `render.html`, runs mermaid and draws itself
 * into a bitmap. The view is never attached to a window, so it gets an explicit measured and laid-out size, and the
 * draw waits for mermaid's render promise (the page calls `Android.done` only after it resolved and two animation
 * frames ran) and then for a post-layout visual state before drawing. Verified on the device only (see the device
 * checklist). The host destroys its view on every path, including its own [timeoutMs] timer, and loads nothing but the
 * bundled asset directory (network loads are blocked and any other request is answered with 403).
 */
class WebViewCapture(
    private val context: Context,
    private val timeoutMs: Long = MermaidRenderer.TIMEOUT_MS,
    private val viewFactory: (Context) -> WebView = ::WebView,
) : WebCapture {
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("TooGenericExceptionCaught") // creating a web view fails in many ways when the system WebView is missing or broken
    override fun start(
        source: String,
        widthPx: Int,
        onDone: (CaptureResult) -> Unit,
    ) {
        main.post {
            val view =
                try {
                    viewFactory(context)
                } catch (e: Exception) {
                    onDone(CaptureResult.Failed(DiagramFailure.WebViewUnavailable(e.message ?: e.javaClass.simpleName)))
                    return@post
                }
            var finished = false
            val notes = CaptureNotes()

            @Suppress("TooGenericExceptionCaught") // destroying a broken web view must not hide the result
            fun finish(result: CaptureResult) {
                if (finished) return
                finished = true
                try {
                    view.destroy()
                } catch (e: Exception) {
                    Log.w(DiagramFailure.TAG, "web view destroy failed", e)
                }
                onDone(result)
            }

            fun failWith(failure: DiagramFailure) = finish(CaptureResult.Failed(failure))
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.blockNetworkLoads = true
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.addJavascriptInterface(
                CaptureBridge(
                    post = { main.post(it) },
                    onDone = { heightCss -> if (!finished) drawAfterFrame(view, widthPx, heightCss, { finished }, ::finish) },
                    onFail = { kind, message ->
                        notes.js(message)
                        failWith(if (kind == KIND_PARSE) DiagramFailure.Parse(message) else DiagramFailure.WebViewUnavailable(message))
                    },
                ),
                "Android",
            )
            view.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        v: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        val allowed = isAllowedUrl(url)
                        Log.d(DiagramFailure.TAG, (if (allowed) "allow " else "block ") + url)
                        if (allowed) return null
                        notes.blocked(url)
                        return forbidden()
                    }

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
                        Log.w(DiagramFailure.TAG, "load error ${error.description} for ${request.url}")
                        notes.loadError(request.url.toString(), error.description.toString())
                        if (request.isForMainFrame) failWith(DiagramFailure.WebViewUnavailable("load error ${error.description}"))
                    }

                    override fun onRenderProcessGone(
                        v: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        failWith(DiagramFailure.WebViewUnavailable("web view process gone"))
                        return true
                    }
                }
            view.webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d(DiagramFailure.TAG, "console ${message.messageLevel()}: ${message.message()}")
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) notes.js(message.message())
                        return true
                    }
                }
            // Fires before the renderer's own wait ends, so the failure carries the hint.
            main.postDelayed({ failWith(DiagramFailure.Timeout(timeoutMs, notes.hint())) }, (timeoutMs - HOST_EARLY_MS).coerceAtLeast(1))
            layoutAt(view, widthPx, INITIAL_HEIGHT)
            view.loadUrl("file:///android_asset/${MermaidRenderer.ASSET_DIR}/render.html")
        }
    }

    private fun layoutAt(
        view: WebView,
        widthPx: Int,
        heightPx: Int,
    ) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
    }

    /** Lays the view out at the diagram height, waits for a post-layout frame, then draws (a timer covers a view that never gets one). */
    private fun drawAfterFrame(
        view: WebView,
        widthPx: Int,
        heightCss: Int,
        isFinished: () -> Boolean,
        finish: (CaptureResult) -> Unit,
    ) {
        val heightPx = (heightCss * context.resources.displayMetrics.density).toInt().coerceIn(1, MAX_HEIGHT)
        layoutAt(view, widthPx, heightPx)
        var drawn = false

        @Suppress("TooGenericExceptionCaught") // a bitmap that cannot be allocated or drawn is a failed diagram, not a crash
        fun drawOnce() {
            if (drawn || isFinished()) return
            drawn = true
            try {
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                view.draw(Canvas(bitmap))
                finish(CaptureResult.Success(bitmap))
            } catch (e: Exception) {
                finish(CaptureResult.Failed(DiagramFailure.Crashed(e.message ?: e.javaClass.simpleName)))
            } catch (e: OutOfMemoryError) {
                finish(CaptureResult.Failed(DiagramFailure.Crashed("out of memory drawing ${widthPx}x$heightPx: ${e.message}")))
            }
        }
        main.postDelayed({ drawOnce() }, FRAME_FALLBACK_MS)
        view.postVisualStateCallback(
            System.nanoTime(),
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    main.post { drawOnce() }
                }
            },
        )
    }

    companion object {
        const val KIND_PARSE = "parse"
        private const val ALLOWED_PREFIX = "file:///android_asset/${MermaidRenderer.ASSET_DIR}/"

        private fun forbidden() = WebResourceResponse("text/plain", "utf-8", HTTP_FORBIDDEN, "Forbidden", emptyMap(), null)

        /** The only URLs the view may load: the bundled mermaid asset directory. */
        fun isAllowedUrl(url: String): Boolean = url.startsWith(ALLOWED_PREFIX) && !url.contains("..")

        private const val HOST_EARLY_MS = 500L
        private const val HTTP_FORBIDDEN = 403
        private const val INITIAL_HEIGHT = 800
        private const val MAX_HEIGHT = 8_000
        private const val FRAME_FALLBACK_MS = 700L
    }
}

/**
 * What the page was seen doing, for the timeout caption. A blocked request is the most telling (the page's own script
 * never ran), then a load error, then the first console or script error.
 */
class CaptureNotes {
    private var blocked: String? = null
    private var loadError: String? = null
    private var js: String? = null

    fun blocked(url: String) {
        if (blocked == null) blocked = url
    }

    fun loadError(
        url: String,
        description: String,
    ) {
        if (loadError == null) loadError = "$description $url"
    }

    fun js(message: String) {
        if (js == null) js = message
    }

    fun hint(): String = blocked?.let { "blocked: $it" } ?: loadError?.let { "load error: $it" } ?: js?.let { "js: $it" } ?: ""
}

/**
 * The `Android` object the page calls. Its callbacks arrive on a web view thread and must reach the main thread through
 * [post] (the host's main handler), never through `View.post`: the capture view is never attached to a window, and
 * `View.post` on an unattached view queues the runnable until attach, so it never ran and every render timed out.
 */
class CaptureBridge(
    private val post: (Runnable) -> Unit,
    private val onDone: (Int) -> Unit,
    private val onFail: (String, String) -> Unit,
) {
    @JavascriptInterface
    fun done(heightCss: Int) {
        post(Runnable { onDone(heightCss) })
    }

    @JavascriptInterface
    fun fail(
        kind: String,
        message: String,
    ) {
        post(Runnable { onFail(kind, message) })
    }
}
