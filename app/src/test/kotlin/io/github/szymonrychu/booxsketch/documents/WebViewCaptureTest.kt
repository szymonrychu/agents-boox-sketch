package io.github.szymonrychu.booxsketch.documents

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebViewCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun the_pages_own_urls_are_allowed() {
        // The exact URLs render.html and its script load as (the page is loaded from file:///android_asset/).
        assertThat(WebViewCapture.isAllowedUrl("file:///android_asset/mermaid/render.html")).isTrue()
        assertThat(WebViewCapture.isAllowedUrl("file:///android_asset/mermaid/mermaid.min.js")).isTrue()
    }

    @Test
    fun remote_and_foreign_urls_are_blocked() {
        listOf(
            "https://cdn.example.com/mermaid.min.js",
            "http://example.com/",
            "https://appassets.androidplatform.net/assets/mermaid/render.html",
            "file:///android_asset/other/x.js",
            "file:///android_asset/mermaid/../secret",
            "file:///sdcard/x.html",
        ).forEach { assertThat(WebViewCapture.isAllowedUrl(it)).isFalse() }
    }

    @Test
    fun a_page_callback_on_a_never_attached_view_reaches_the_host() {
        val view = WebView(context)
        assertThat(view.isAttachedToWindow).isFalse()
        var result: CaptureResult? = null
        WebViewCapture(context, 30_000, { view }).start("graph TD; A-->B", 400) { result = it }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val bridge = shadowOf(view).getJavascriptInterface("Android")!!
        bridge.javaClass.getMethod("fail", String::class.java, String::class.java).invoke(bridge, "parse", "boom")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val failure = (result as CaptureResult.Failed).failure
        assertThat(failure).isEqualTo(DiagramFailure.Parse("boom"))
    }

    @Test
    fun the_timeout_caption_names_what_was_blocked_or_logged() {
        assertThat(DiagramFailure.Timeout(20_000, "blocked: https://x.test/a.js").caption)
            .isEqualTo("diagram failed: timed out after 20 s (blocked: https://x.test/a.js)")
        assertThat(DiagramFailure.Timeout(20_000).caption).isEqualTo("diagram failed: timed out after 20 s")
        val notes = CaptureNotes()
        notes.js("first")
        notes.js("second")
        assertThat(notes.hint()).isEqualTo("js: first")
        notes.loadError("u", "net::ERR")
        assertThat(notes.hint()).isEqualTo("load error: net::ERR u")
        notes.blocked("https://a")
        assertThat(notes.hint()).isEqualTo("blocked: https://a")
    }
}
