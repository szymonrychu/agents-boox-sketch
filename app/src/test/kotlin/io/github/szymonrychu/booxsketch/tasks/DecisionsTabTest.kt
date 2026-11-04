package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.tasks.FakeRelay.Companion.json
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class DecisionsTabTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var relay: FakeRelay
    private lateinit var scope: CoroutineScope
    private var task = taskWith(openDecision())

    @Before
    fun setUp() {
        relay =
            FakeRelay { r ->
                if (r.method ==
                    "POST"
                ) {
                    json("""{"id":21,"question":"q","status":"answered","answer":{"choice":0,"label":"A"}}""")
                } else {
                    json(task)
                }
            }
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        relay.shutdown()
    }

    private fun show(): TaskDetailViewModel {
        val vm = TaskDetailViewModel(relay.client, "T-12", emptyFlow(), scope)
        compose.setContent { EinkTheme { TaskDetailScreen(vm, {}, {}) } }
        compose.waitUntil(5_000) { vm.state.value.task != null }
        compose.onNodeWithTag(subTabTag(SubTab.DECISIONS)).performClick()
        return vm
    }

    private fun post() = relay.requests.first { it.method == "POST" }

    private fun postBody() = Json.parseToJsonElement(post().body.clone().readUtf8()).jsonObject

    private fun await(ok: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!ok()) delay(10) } }

    @Test
    fun option_and_own_text_are_mutually_exclusive() {
        show()
        compose.onNodeWithTag(decisionOptionTag(21, 1)).performClick()
        compose.onNodeWithTag(decisionSubmitTag(21)).assertIsEnabled()
        compose.onNodeWithTag(decisionTextTag(21)).performTextInput("my own")
        // typing clears the option; choosing an option clears the text
        compose.onNodeWithTag(decisionSubmitTag(21)).performClick()
        await { relay.requests.any { it.method == "POST" } }
        assertThat(postBody().keys).containsExactly("text")
        relay.requests.clear()
        compose.onNodeWithTag(decisionOptionTag(21, 0)).performClick()
        compose.onNodeWithTag(decisionTextTag(21)).assertTextEquals("Own answer", "")
    }

    @Test
    fun submit_disabled_until_choice_or_text() {
        show()
        compose.onNodeWithTag(decisionSubmitTag(21)).assertIsNotEnabled()
        compose.onNodeWithTag(decisionTextTag(21)).performTextInput("  ")
        compose.onNodeWithTag(decisionSubmitTag(21)).assertIsNotEnabled()
        compose.onNodeWithTag(decisionOptionTag(21, 0)).performClick()
        compose.onNodeWithTag(decisionSubmitTag(21)).assertIsEnabled()
    }

    @Test
    fun submit_sends_choice_only() {
        show()
        compose.onNodeWithTag(decisionOptionTag(21, 1)).performClick()
        compose.onNodeWithTag(decisionSubmitTag(21)).performClick()
        await { relay.requests.any { it.method == "POST" } }
        assertThat(post().path).isEqualTo("/app/v1/sketch/tasks/T-12/decisions/21/answer")
        val body = postBody()
        assertThat(body.keys).containsExactly("choice")
        assertThat(body["choice"]!!.jsonPrimitive.content).isEqualTo("1")
    }

    @Test
    fun submit_sends_text_with_note() {
        show()
        compose.onNodeWithTag(decisionTextTag(21)).performTextInput("Something else")
        compose.onNodeWithTag(decisionNoteTag(21)).performTextInput("because")
        compose.onNodeWithTag(decisionSubmitTag(21)).performClick()
        await { relay.requests.any { it.method == "POST" } }
        val body = postBody()
        assertThat(body["text"]!!.jsonPrimitive.content).isEqualTo("Something else")
        assertThat(body["note"]!!.jsonPrimitive.content).isEqualTo("because")
        assertThat(body.keys).doesNotContain("choice")
    }

    @Test
    fun double_tap_submit_sends_one_request() {
        val vm = show()
        vm.answer(21, 0, null, null)
        vm.answer(21, 0, null, null)
        vm.answer(21, 0, null, null)
        await { relay.requests.any { it.method == "POST" } }
        runBlocking { delay(300) }
        assertThat(relay.requests.count { it.method == "POST" }).isEqualTo(1)
    }

    @Test
    fun already_answered_shows_message_and_reloads() {
        val vm = show()
        relay.handler = { r ->
            if (r.method == "POST") FakeRelay.taskboardError(409, "already_answered", "decision is already answered") else json(task)
        }
        val gets = relay.requests.count { it.method == "GET" }
        vm.answer(21, 0, null, null)
        await {
            vm.state.value.decisionNotice
                .containsKey(21L)
        }
        compose.onNodeWithText("Already answered").assertExists()
        await { relay.requests.count { it.method == "GET" } > gets }
    }

    @Test
    fun answered_card_shows_answer_and_note() {
        task = taskWith(answeredDecision())
        show()
        compose.onNodeWithTag(decisionAnswerTag(22)).assertTextContains("Answer: No")
        compose.onNodeWithText("Note: No badge on e-ink").assertExists()
        compose.onNodeWithTag(decisionSubmitTag(22)).assertDoesNotExist()
    }

    @Test
    fun withdrawn_card_shows_withdrawn() {
        task = taskWith(WITHDRAWN_DECISION)
        show()
        compose.onNodeWithTag(decisionWithdrawnTag(24)).assertExists()
        compose.onNodeWithText("Withdrawn").assertExists()
    }

    @Test
    fun recommended_chip_shown_and_asked_by_context() {
        show()
        compose.onNodeWithText("Recommended").assertExists()
        compose.onNodeWithText("Asked by alpha-fox").assertExists()
        compose.onNodeWithText("E-ink friendly.").assertExists()
    }

    @Test
    fun task_with_no_decisions_shows_empty_text() {
        task = taskWith()
        show()
        compose.onNodeWithTag(TAG_DECISIONS_EMPTY).assertExists()
    }
}

internal fun openDecision() =
    """{"id":21,"asking_slug":"alpha-fox","question":"Which paging style?","context":"E-ink friendly.","status":"open",""" +
        """"options":[{"label":"Prev/Next","description":"Buttons","recommended":true},{"label":"Infinite scroll"}]}"""

internal fun answeredDecision() =
    """{"id":22,"asking_slug":"alpha-fox","question":"Keep the badge?","status":"answered","options":[{"label":"Yes"},{"label":"No"}],""" +
        """"answer":{"choice":1,"label":"No"},"note":"No badge on e-ink"}"""

internal const val WITHDRAWN_DECISION =
    """{"id":24,"question":"Dropped question","status":"withdrawn","options":[{"label":"A"},{"label":"B"}]}"""

internal fun taskWith(vararg decisions: String) = taskJson("T-12", "decisions" to decisions.joinToString(",", "[", "]"))
