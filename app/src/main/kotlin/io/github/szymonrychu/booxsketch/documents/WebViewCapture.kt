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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * The real host: a software-layer [WebView] on the main thread that loads `render.html`, runs mermaid and draws itself
 * into a bitmap. The view is never attached to a window, so it gets an explicit measured and laid-out size, and the
 * draw waits for mermaid's render promise (the page calls `Android.done` only after it resolved and two animation
 * frames ran) and then for a post-layout visual state before drawing. Verified on the device only (see the device
 * checklist).
 */
class WebViewCapture(
    private val context: Context,
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
                    WebView(context)
                } catch (e: Exception) {
                    onDone(CaptureResult.Failed(DiagramFailure.WebViewUnavailable(e.message ?: e.javaClass.simpleName)))
                    return@post
                }
            var finished = false

            fun finish(result: CaptureResult) {
                if (finished) return
                finished = true
                view.destroy()
                onDone(result)
            }

            fun failWith(failure: DiagramFailure) = finish(CaptureResult.Failed(failure))
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun done(heightCss: Int) {
                        view.post { drawAfterFrame(view, widthPx, heightCss, ::finish) }
                    }

                    @JavascriptInterface
                    fun fail(
                        kind: String,
                        message: String,
                    ) {
                        view.post {
                            failWith(
                                if (kind == KIND_PARSE) DiagramFailure.Parse(message) else DiagramFailure.WebViewUnavailable(message),
                            )
                        }
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
        finish: (CaptureResult) -> Unit,
    ) {
        val heightPx = (heightCss * context.resources.displayMetrics.density).toInt().coerceIn(1, MAX_HEIGHT)
        layoutAt(view, widthPx, heightPx)
        var drawn = false

        fun drawOnce() {
            if (drawn) return
            drawn = true
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            view.draw(Canvas(bitmap))
            finish(CaptureResult.Success(bitmap))
        }
        main.postDelayed({ drawOnce() }, FRAME_FALLBACK_MS)
        view.postVisualStateCallback(
            System.nanoTime(),
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    view.postOnAnimation { drawOnce() }
                }
            },
        )
    }

    companion object {
        const val KIND_PARSE = "parse"
        private const val INITIAL_HEIGHT = 800
        private const val MAX_HEIGHT = 8_000
        private const val FRAME_FALLBACK_MS = 700L
    }
}
