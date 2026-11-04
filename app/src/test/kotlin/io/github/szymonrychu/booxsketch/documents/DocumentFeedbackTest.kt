package io.github.szymonrychu.booxsketch.documents

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class DocumentFeedbackTest {
    @get:Rule val compose = createComposeRule()

    private val items =
        listOf(
            FeedbackEntity("s", 2, "question", "Which box does the arrow point to?", "top right", 2, "x"),
            FeedbackEntity("s", 1, "note", "[[red]] items are in red ink", null, null, "x"),
        )

    private fun show(feedback: List<FeedbackEntity>) {
        val state = DocumentViewState(2, 2, emptyList(), 0, false, loaded = true)
        compose.setContent {
            EinkTheme { DocumentScreen("Doc", state, listOf(1, 2), {}, {}, {}, {}, {}, {}, feedback = feedback, pageArea = {}) }
        }
    }

    @Test
    fun without_feedback_nothing_is_shown() {
        show(emptyList())
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_LATEST).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_TOGGLE).assertDoesNotExist()
    }

    @Test
    fun the_latest_item_is_shown_and_opens_the_list() {
        show(items)
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_LATEST).assertIsDisplayed().assertTextContains("Question: Which box", substring = true)
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_PANEL).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_LATEST).performClick()
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_PANEL).assertIsDisplayed()
        compose.onNodeWithTag(feedbackItemTag(2)).assertTextContains("top right", substring = true)
        compose.onNodeWithTag(feedbackItemTag(1)).assertTextContains("[red] items are in red ink", substring = true)
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_CLOSE).performClick()
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_PANEL).assertDoesNotExist()
    }

    @Test
    fun the_toggle_button_shows_the_count_and_opens_the_list() {
        show(items)
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_TOGGLE).assertTextContains("Notes (2)")
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_TOGGLE).performClick()
        compose.onNodeWithTag(TAG_DOC_FEEDBACK_PANEL).assertIsDisplayed()
    }

    @Test
    fun colour_markers_become_coloured_labels() {
        val text = feedbackAnnotated("[[blue]] box then [[?x]]")
        assertThat(text.text).isEqualTo("[blue] box then [[?x]]")
        val span = text.spanStyles.single()
        assertThat(span.item.color).isEqualTo(Color(PenColor.BLUE.argb))
        assertThat(span.start).isEqualTo(0)
        assertThat(span.end).isEqualTo("[blue]".length)
    }

    @Test
    fun kind_labels() {
        assertThat(feedbackKindLabel("illegible")).isEqualTo("Illegible")
    }
}
