package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
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
        feedback: List<FeedbackEntity> = emptyList(),
    ) {
        val p = page()
        val state = DocumentViewState(version, 3, listOf(p, p, p), 1, readOnly, loaded = true)
        compose.setContent {
            EinkTheme { DocumentScreen("Settings page", state, listOf(1, 2, 3), {}, {}, {}, {}, {}, {}, feedback = feedback) }
        }
    }

    private val feedback =
        listOf(
            FeedbackEntity("s", 3, "question", "Does the arrow point to Save or to Cancel?", "bottom right", 3, "x"),
            FeedbackEntity("s", 2, "uncertain", "Read the second label as Settings; it could be Setting.", "top", 3, "x"),
            FeedbackEntity("s", 1, "note", "[[red]] items are marked in red ink, [[blue]] items in blue.", null, 3, "x"),
        )

    @Test
    fun document_with_latest_feedback() {
        content(readOnly = false, feedback = feedback)
        compose.onRoot().captureRoboImage("src/test/screenshots/document_with_latest_feedback.png")
    }

    @Test
    fun document_feedback_list() {
        content(readOnly = false, feedback = feedback)
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_LATEST).performClick()
        compose.onRoot().captureRoboImage("src/test/screenshots/document_feedback_list.png")
    }

    @Test
    fun document_page_with_colour_markers() {
        val md = "# Wireframe\n\n- [[red]] Delete account\n- [[blue]] Save\n- [[green]] Export\n- Cancel"
        val rendered = MarkdownPaginator(layout).paginate(md).first()
        val file = File(tmp.root, "colour.0.png")
        file.outputStream().use { rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val state = DocumentViewState(3, 3, listOf(FrozenPage(file, rendered.sourceLines)), 0, true, loaded = true)
        compose.setContent { EinkTheme { DocumentScreen("Wireframe", state, listOf(1, 2, 3), {}, {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/document_page_with_colour_markers.png")
    }

    @Test
    fun document_page_portrait() {
        content(readOnly = false)
        compose.onRoot().captureRoboImage("src/test/screenshots/document_page_portrait.png")
    }

    @Test
    fun version_picker_with_revert() {
        val p = page()
        val state = DocumentViewState(1, 3, listOf(p, p, p), 1, readOnly = true, loaded = true)
        compose.setContent {
            EinkTheme { DocumentScreen("Settings page", state, listOf(1, 2, 3), {}, {}, {}, {}, {}, {}, onRevert = {}) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/version_picker_with_revert.png")
    }

    @Test
    fun revert_asks_for_confirmation_first() {
        val p = page()
        val reverted = mutableListOf<Int>()
        val state = DocumentViewState(1, 3, listOf(p, p, p), 1, readOnly = true, loaded = true)
        compose.setContent {
            EinkTheme { DocumentScreen("Settings page", state, listOf(1, 2, 3), {}, {}, {}, {}, {}, {}, onRevert = { reverted += it }) }
        }
        compose.onNodeWithTag(TAG_DOC_REVERT).performClick()
        compose.onNodeWithText("Revert to version 1? This adds version 4 with the text of version 1.").assertExists()
        assertThat(reverted).isEmpty()
        compose.onNodeWithTag(TAG_DOC_REVERT_CONFIRM).performClick()
        assertThat(reverted).containsExactly(1)
    }

    @Test
    fun no_revert_on_the_latest_version() {
        content(readOnly = false)
        compose.onNodeWithTag(TAG_DOC_REVERT).assertDoesNotExist()
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
