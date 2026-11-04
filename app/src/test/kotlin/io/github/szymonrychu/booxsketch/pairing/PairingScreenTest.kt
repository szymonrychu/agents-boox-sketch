package io.github.szymonrychu.booxsketch.pairing

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.ConfigDto
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class PairingScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<Pair<String, String>>()
    private var paired = 0

    private fun show(result: PairResult = PairResult.Ok(ConfigDto(1, 2, 3, 4, 5, 6, 1))) {
        compose.setContent {
            EinkTheme {
                PairingScreen(
                    pair = { origin, code ->
                        calls += origin to code
                        result
                    },
                    onPaired = { paired++ },
                )
            }
        }
    }

    private fun press(digits: String) = digits.forEach { compose.onNodeWithTag(keyTag(it)).performClick() }

    private fun box(i: Int) = compose.onNodeWithTag(boxTag(i))

    @Test
    fun digits_fill_boxes_in_order_and_cap_at_six() {
        show()
        press("12345")
        (0..4).forEach { box(it).assertTextEquals("${it + 1}") }
        box(5).assertTextEquals("")
        press("678")
        box(5).assertTextEquals("6")
        box(0).assertTextEquals("1")
    }

    @Test
    fun backspace_removes_the_last_digit_and_clear_removes_all() {
        show()
        press("123")
        compose.onNodeWithTag(TAG_KEY_BACKSPACE).performClick()
        box(2).assertTextEquals("")
        box(1).assertTextEquals("2")
        compose.onNodeWithTag(TAG_KEY_CLEAR).performClick()
        box(0).assertTextEquals("")
    }

    @Test
    fun pair_is_enabled_only_with_an_origin_and_six_digits() {
        show()
        compose.onNodeWithTag(TAG_ORIGIN).performTextInput("connector.example")
        press("12345")
        compose.onNodeWithTag(TAG_PAIR).assertIsNotEnabled()
        press("6")
        compose.onNodeWithTag(TAG_PAIR).assertIsEnabled()
    }

    @Test
    fun pair_without_origin_stays_disabled() {
        show()
        press("123456")
        compose.onNodeWithTag(TAG_PAIR).assertIsNotEnabled()
    }

    @Test
    fun success_submits_origin_and_code_once_and_calls_on_paired() {
        show()
        compose.onNodeWithTag(TAG_ORIGIN).performTextInput("connector.example")
        press("123456")
        compose.onNodeWithTag(TAG_PAIR).performClick()
        compose.waitForIdle()
        assertThat(calls).containsExactly("connector.example" to "123456")
        assertThat(paired).isEqualTo(1)
    }

    @Test
    fun failure_clears_the_digits_and_shows_the_error_without_the_code() {
        show(PairResult.CodeRejected)
        compose.onNodeWithTag(TAG_ORIGIN).performTextInput("connector.example")
        press("123456")
        compose.onNodeWithTag(TAG_PAIR).performClick()
        compose.waitForIdle()
        (0..5).forEach { box(it).assertTextEquals("") }
        compose.onNodeWithTag(TAG_PAIR).assertIsNotEnabled()
        assertThat(paired).isEqualTo(0)
        val text =
            compose
                .onNodeWithTag(TAG_PAIRING_ERROR)
                .fetchSemanticsNode()
                .config
                .toString()
        assertThat(text).contains("expired")
        assertThat(text).doesNotContain("123456")
    }

    @Test
    fun no_auto_submit_when_the_sixth_digit_is_typed() {
        show()
        compose.onNodeWithTag(TAG_ORIGIN).performTextInput("connector.example")
        press("123456")
        compose.waitForIdle()
        assertThat(calls).isEmpty()
    }
}
