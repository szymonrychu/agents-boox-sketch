package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class DocumentScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val layout = DocumentLayout(1404, 1872)

    private fun page(): FrozenPage {
        val md = "# Settings page\n\nA short paragraph with **bold** text.\n\n- first item\n- second item\n\n```\nval x = 1\n```"
        val rendered = MarkdownPaginator(layout).paginate(md).first()
        val file = File(tmp.root, "page.0.png")
        file.outputStream().use { rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FrozenPage(file, rendered.sourceLines)
    }

    private fun content(
        readOnly: Boolean,
        version: Int = 3,
    ) {
        val p = page()
        val state = DocumentViewState(version, 3, listOf(p, p, p), 1, readOnly, loaded = true)
        compose.setContent {
            EinkTheme { DocumentScreen("Settings page", state, listOf(1, 2, 3), {}, {}, {}, {}, {}, {}) }
        }
    }

    @Test
    fun document_page_portrait() {
        content(readOnly = false)
        compose.onRoot().captureRoboImage("src/test/screenshots/document_page_portrait.png")
    }

    @Test
    fun document_older_version_read_only() {
        content(readOnly = true, version = 1)
        compose.onRoot().captureRoboImage("src/test/screenshots/document_older_version_read_only.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class DocumentLandscapeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun document_page_landscape() {
        val bitmap = Bitmap.createBitmap(1404, 1872, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File(tmp.root, "page.0.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val state = DocumentViewState(3, 3, listOf(FrozenPage(file, 0..1)), 0, false, loaded = true)
        compose.setContent { EinkTheme { DocumentScreen("Settings page", state, listOf(1, 2, 3), {}, {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/document_page_landscape.png")
    }
}
