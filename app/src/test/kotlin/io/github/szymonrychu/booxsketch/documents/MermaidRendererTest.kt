package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MermaidRendererTest {
    private fun bitmap(
        w: Int,
        h: Int,
        paint: Boolean = true,
    ) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        if (paint) setPixel(w / 2, h / 2, Color.BLACK)
    }

    private fun ok(b: Bitmap) = CaptureResult.Success(b)

    private fun failed(f: DiagramFailure) = CaptureResult.Failed(f)

    private fun failure(r: DiagramResult) = (r as DiagramResult.Failed).failure

    @Test
    fun timeout_names_the_reason() {
        val renderer = MermaidRenderer({ _, _, _ -> }, timeoutMs = 50)
        val f = failure(renderer.renderDiagram("graph TD; A-->B", 400))
        assertThat(f).isEqualTo(DiagramFailure.Timeout(50))
        assertThat(renderer.render("graph TD; A-->B", 400)).isNull()
    }

    @Test
    fun blank_capture_is_retried_once_then_names_the_reason() {
        var calls = 0
        val renderer =
            MermaidRenderer({ _, w, done ->
                calls++
                done(ok(bitmap(w, 100, paint = false)))
            })
        assertThat(failure(renderer.renderDiagram("graph TD; A-->B", 400))).isEqualTo(DiagramFailure.Blank)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun blank_first_capture_then_good_one_gives_the_image() {
        var calls = 0
        val renderer =
            MermaidRenderer({ _, w, done ->
                calls++
                done(ok(bitmap(w, 100, paint = calls > 1)))
            })
        assertThat(renderer.renderDiagram("graph TD; A-->B", 400)).isInstanceOf(DiagramResult.Image::class.java)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun parse_error_carries_mermaids_message_and_is_not_retried() {
        var calls = 0
        val renderer =
            MermaidRenderer({ _, _, done ->
                calls++
                done(failed(DiagramFailure.Parse("Parse error on line 2\n  ---^")))
            })
        val f = failure(renderer.renderDiagram("graph TD; A->", 400))
        assertThat(f.caption).isEqualTo("diagram failed: mermaid parse error: Parse error on line 2")
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun web_view_unavailable_from_a_failing_or_throwing_host() {
        val reported = MermaidRenderer({ _, _, done -> done(failed(DiagramFailure.WebViewUnavailable("no webview"))) })
        assertThat(failure(reported.renderDiagram("x", 400)).caption).isEqualTo("diagram failed: web view unavailable: no webview")
        val thrown = MermaidRenderer({ _, _, _ -> error("boom") })
        assertThat(failure(thrown.renderDiagram("x", 400)).caption).isEqualTo("diagram failed: web view unavailable: boom")
    }

    @Test
    fun timeout_default_is_long_enough_for_a_cold_e_ink_web_view() {
        assertThat(MermaidRenderer.TIMEOUT_MS).isAtLeast(15_000L)
    }

    @Test
    fun returns_bitmap_of_requested_width_when_capture_succeeds() {
        val renderer = MermaidRenderer({ _, _, done -> done(ok(bitmap(300, 120))) })
        val result = renderer.render("graph TD; A-->B", 600)!!
        assertThat(result.width).isEqualTo(600)
        assertThat(result.height).isEqualTo(240)
    }

    @Test
    fun asset_is_bundled_and_pinned() {
        val dir = File("src/main/assets/mermaid")
        assertThat(File(dir, "render.html").isFile).isTrue()
        val js = File(dir, "mermaid.min.js")
        assertThat(js.isFile).isTrue()
        val sha = MessageDigest.getInstance("SHA-256").digest(js.readBytes()).joinToString("") { "%02x".format(it) }
        assertThat(sha).isEqualTo(MermaidRenderer.MERMAID_SHA256)
    }
}
