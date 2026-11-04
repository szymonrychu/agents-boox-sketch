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

    @Test
    fun returns_null_on_timeout() {
        val renderer = MermaidRenderer({ _, _, _ -> }, timeoutMs = 50)
        assertThat(renderer.render("graph TD; A-->B", 400)).isNull()
    }

    @Test
    fun returns_null_for_blank_capture() {
        val renderer = MermaidRenderer({ _, w, done -> done(bitmap(w, 100, paint = false)) })
        assertThat(renderer.render("graph TD; A-->B", 400)).isNull()
    }

    @Test
    fun returns_null_when_the_host_fails() {
        assertThat(MermaidRenderer({ _, _, done -> done(null) }).render("x", 400)).isNull()
        assertThat(MermaidRenderer({ _, _, _ -> error("no web view") }).render("x", 400)).isNull()
    }

    @Test
    fun returns_bitmap_of_requested_width_when_capture_succeeds() {
        val renderer = MermaidRenderer({ _, _, done -> done(bitmap(300, 120)) })
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
