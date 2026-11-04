package io.github.szymonrychu.booxsketch.documents

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class DocumentsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun documents_list() {
        val rows =
            listOf(
                DocumentRow("a", "srv-a", "Settings page sketch", 3, "2026-11-04T10:03:00Z", DocState.IDLE),
                DocumentRow("b", "srv-b", "Login form", 1, "2026-11-03T08:00:00Z", DocState.TRANSLATING),
                DocumentRow("c", "srv-c", "Checkout flow", 2, "2026-11-02T08:00:00Z", DocState.FAILED),
            )
        compose.setContent {
            EinkTheme { DocumentsScreen(DocumentsPage(rows, 0, hasPrev = false, hasNext = true, loaded = true), {}, {}, {}) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/documents_list.png")
    }

    @Test
    fun documents_list_empty() {
        compose.setContent { EinkTheme { DocumentsScreen(DocumentsPage(loaded = true), {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/documents_list_empty.png")
    }
}
