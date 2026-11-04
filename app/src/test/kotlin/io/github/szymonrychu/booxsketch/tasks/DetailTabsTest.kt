package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.style.TextDecoration
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val TODO_ITEMS =
    """[{"id":7,"text":"Write client","status":"done","position":0},{"id":8,"text":"Write screens","status":"inprogress","position":1}]"""
private const val COMMENTS =
    """[{"id":3,"author_kind":"user","body":"Looks good","created_at":"2026-11-02T09:00:00Z"},""" +
        """{"id":4,"author_kind":"agent","author_slug":"alpha-fox","body":"Done","created_at":"2026-11-02T09:30:00Z"}]"""

internal fun eventsJson(count: Int) =
    (1..count).joinToString(",", "[", "]") {
        """{"id":$it,"actor_kind":"agent","actor_slug":"alpha-fox","kind":"todo_updated","at":"2026-11-01T10:%02d:00Z"}""".format(it % 60)
    }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class DetailTabsTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var relay: FakeRelay
    private lateinit var scope: CoroutineScope
    private var task = taskJson("T-12", "todo" to TODO_ITEMS, "comments" to COMMENTS, "events" to eventsJson(2))

    @Before
    fun setUp() {
        relay = FakeRelay { json(task) }
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        relay.shutdown()
    }

    private fun show(tab: SubTab): TaskDetailViewModel {
        val vm = TaskDetailViewModel(relay.client, "T-12", emptyFlow(), scope)
        compose.setContent { EinkTheme { TaskDetailScreen(vm, {}, {}) } }
        compose.waitUntil(5_000) { vm.state.value.task != null }
        compose.onNodeWithTag(subTabTag(tab)).performClick()
        return vm
    }

    private fun await(ok: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!ok()) delay(10) } }

    private fun lastWrite() = relay.requests.last { it.method != "GET" }

    private fun lastBody() = Json.parseToJsonElement(lastWrite().body.clone().readUtf8()).jsonObject

    @Test
    fun status_tap_sends_patch_for_that_item() {
        show(SubTab.TODO)
        compose.onNodeWithTag(todoStatusTag(8, "done")).performClick()
        await { relay.requests.any { it.method == "PATCH" } }
        assertThat(lastWrite().path).isEqualTo("/app/v1/sketch/tasks/T-12/todo/8")
        assertThat(lastBody().toString()).isEqualTo("""{"status":"done"}""")
    }

    @Test
    fun done_item_is_struck_through_and_not_colour_only() {
        show(SubTab.TODO)
        compose.onNodeWithText("Done: Write client").assertExists()
        val node = compose.onNodeWithTag(todoTextTag(7)).fetchSemanticsNode()
        val styles = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        assertThat(styles).isNotEmpty()
        compose.onNodeWithText("Write screens").assertExists()
        assertThat(TextDecoration.LineThrough).isNotNull()
    }

    @Test
    fun add_item_sends_whole_list_with_new_item() {
        show(SubTab.TODO)
        compose.onNodeWithTag(TAG_TODO_FIELD).performTextInput("Ship it")
        compose.onNodeWithTag(TAG_TODO_ADD).performClick()
        await { relay.requests.any { it.method == "PUT" } }
        assertThat(lastWrite().path).isEqualTo("/app/v1/sketch/tasks/T-12/todo")
        val items = lastBody()["items"]!!.jsonArray.map { it.jsonObject }
        assertThat(items.map { it["text"]!!.jsonPrimitive.content }).containsExactly("Write client", "Write screens", "Ship it").inOrder()
        assertThat(items.map { it["status"]!!.jsonPrimitive.content }).containsExactly("done", "inprogress", "todo").inOrder()
    }

    @Test
    fun remove_item_sends_list_without_it() {
        show(SubTab.TODO)
        compose.onNodeWithTag(todoRemoveTag(7)).performClick()
        await { relay.requests.any { it.method == "PUT" } }
        val items = lastBody()["items"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertThat(items).containsExactly("Write screens")
    }

    @Test
    fun item_over_200_rejected_and_newline_stripped() {
        val vm = show(SubTab.TODO)
        vm.addTodo("a".repeat(201))
        assertThat(vm.state.value.todoError).isNotNull()
        assertThat(relay.requests.none { it.method == "PUT" }).isTrue()
        vm.addTodo("line one\nline two")
        await { relay.requests.any { it.method == "PUT" } }
        val items = lastBody()["items"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertThat(items.last()).isEqualTo("line one line two")
    }

    @Test
    fun no_edit_or_reorder_controls() {
        show(SubTab.TODO)
        // the only text input is the add field; each row has three status segments and Remove, nothing else
        compose.onAllNodesWithTagCount(hasSetTextAction(), 1)
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Up").assertDoesNotExist()
        compose.onNodeWithText("Down").assertDoesNotExist()
    }

    @Test
    fun comment_add_clears_field_and_reloads() {
        val vm = show(SubTab.COMMENTS)
        compose.onNodeWithTag(TAG_COMMENT_ADD).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_COMMENT_FIELD).performTextInput("  Nice  ")
        compose.onNodeWithTag(TAG_COMMENT_ADD).assertIsEnabled()
        val extra = """,{"id":5,"author_kind":"user","body":"Nice","created_at":"2026-11-03T09:00:00Z"}]"""
        task = taskJson("T-12", "comments" to COMMENTS.dropLast(1) + extra)
        compose.onNodeWithTag(TAG_COMMENT_ADD).performClick()
        await { relay.requests.any { it.method == "POST" } }
        assertThat(lastWrite().path).isEqualTo("/app/v1/sketch/tasks/T-12/comments")
        assertThat(lastBody().toString()).isEqualTo("""{"body":"Nice"}""")
        await {
            vm.state.value.task
                ?.comments
                ?.size == 3
        }
        compose.onNodeWithTag(TAG_COMMENT_ADD).assertIsNotEnabled()
        compose.onNodeWithText("Nice").assertExists()
    }

    @Test
    fun comments_show_author_slug_or_kind_and_date() {
        show(SubTab.COMMENTS)
        compose.onNodeWithText("alpha-fox  2026-11-02 09:30").assertExists()
        compose.onNodeWithText("user  2026-11-02 09:00").assertExists()
    }

    @Test
    fun history_pages_prev_next() {
        task = taskJson("T-12", "events" to eventsJson(12))
        show(SubTab.HISTORY)
        compose.onNodeWithTag(historyRowTag(12)).assertExists()
        compose.onNodeWithTag(historyRowTag(2)).assertDoesNotExist()
        compose.onNodeWithTag("${TAG_HISTORY_PAGE_PREFIX}_next").performClick()
        compose.onNodeWithTag(historyRowTag(2)).assertExists()
        compose.onNodeWithTag(historyRowTag(12)).assertDoesNotExist()
        compose.onNodeWithTag("${TAG_HISTORY_PAGE_PREFIX}_prev").performClick()
        compose.onNodeWithTag(historyRowTag(12)).assertExists()
        compose.onAllNodesWithText("alpha-fox: todo updated").assertCountEquals(10)
    }

    @Test
    fun empty_states_for_all_three() {
        task = taskJson("T-12")
        show(SubTab.TODO)
        compose.onNodeWithTag(TAG_TODO_EMPTY).assertExists()
        compose.onNodeWithTag(subTabTag(SubTab.COMMENTS)).performClick()
        compose.onNodeWithTag(TAG_COMMENTS_EMPTY).assertExists()
        compose.onNodeWithTag(subTabTag(SubTab.HISTORY)).performClick()
        compose.onNodeWithTag(TAG_HISTORY_EMPTY).assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
        count: Int,
    ) = onAllNodes(matcher).assertCountEquals(count)
}
