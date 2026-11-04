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
class SourcesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private fun thumb(name: String): File {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        return File(tmp.root, name).also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun sources_with_thumbs() {
        val items =
            listOf(
                SourceItem("a", SourceKind.DRAWING, listOf(thumb("a0.png"), thumb("a1.png")), SourceState.LOCAL),
                SourceItem("b", SourceKind.NOTES, listOf(thumb("b0.png")), SourceState.REMOTE_PAGE0),
            )
        compose.setContent { EinkTheme { SourcesScreen(items, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/sources_with_thumbs.png")
    }

    @Test
    fun sources_gone() {
        compose.setContent { EinkTheme { SourcesScreen(listOf(SourceItem("a", SourceKind.DRAWING, emptyList(), SourceState.GONE)), {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/sources_gone.png")
    }
}
