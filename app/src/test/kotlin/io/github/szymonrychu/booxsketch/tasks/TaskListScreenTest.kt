package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.TaskDto
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class TaskListScreenTest {
    @get:Rule val compose = createComposeRule()

    private val json = Json { ignoreUnknownKeys = true }

    private fun task(
        key: String,
        vararg fields: Pair<String, String>,
    ): TaskDto = json.decodeFromString(taskJson(key, *fields))

    private val blocked = task("T-3", "labels" to "[\"boox\",\"ui\"]", "assignee_session" to "\"sess-a\"", "open_decisions" to "2")
    private val plain = task("T-2", "title" to "\"Plain task\"", "status" to "\"in_progress\"")

    private var opened: String? = null
    private var created = 0
    private val labelsAdded = mutableListOf<String>()
    private val sessions = mutableListOf<String>()
    private var prevs = 0
    private var nexts = 0

    private fun show(state: TaskListState) {
        compose.setContent {
            EinkTheme {
                TaskListContent(
                    state = state,
                    onOpen = { opened = it },
                    onCreate = { created++ },
                    onStatus = {},
                    onSession = { sessions += it },
                    onAddLabel = { labelsAdded += it },
                    onRemoveLabel = {},
                    onPrev = { prevs++ },
                    onNext = { nexts++ },
                )
            }
        }
    }

    @Test
    fun rows_show_key_title_status_labels_assignee() {
        show(TaskListState(items = listOf(blocked, plain), loading = false))
        compose.onNodeWithText("T-3").assertExists()
        compose.onNodeWithText("Task T-3").assertExists()
        compose.onNodeWithTag(taskStatusTag("T-3")).assertExists()
        compose.onNodeWithTag(taskRowLabelTag("T-3", "boox")).assertExists()
        compose.onNodeWithTag(taskRowLabelTag("T-3", "ui")).assertExists()
        compose.onNodeWithTag(taskAssigneeTag("T-3")).assertExists()
        compose.onNodeWithText("in progress").assertExists()
        compose.onNodeWithTag(taskAssigneeTag("T-2")).assertDoesNotExist()
    }

    @Test
    fun blocked_chip_shown_only_with_open_decisions() {
        show(TaskListState(items = listOf(blocked, plain), loading = false))
        compose.onNodeWithTag(taskBlockedTag("T-3")).assertExists()
        compose.onNodeWithText("Blocked (2)").assertExists()
        compose.onNodeWithTag(taskBlockedTag("T-2")).assertDoesNotExist()
    }

    @Test
    fun tapping_row_opens_the_task() {
        show(TaskListState(items = listOf(blocked), loading = false))
        compose.onNodeWithTag(taskRowTag("T-3")).performClick()
        assertThat(opened).isEqualTo("T-3")
    }

    @Test
    fun tapping_row_label_adds_filter() {
        show(TaskListState(items = listOf(blocked), loading = false))
        compose.onNodeWithTag(taskRowLabelTag("T-3", "boox")).performClick()
        assertThat(labelsAdded).containsExactly("boox")
    }

    @Test
    fun tapping_assignee_sets_session_filter() {
        show(TaskListState(items = listOf(blocked), loading = false))
        compose.onNodeWithTag(taskAssigneeTag("T-3")).performClick()
        assertThat(sessions).containsExactly("sess-a")
    }

    @Test
    fun prev_next_buttons_state() {
        show(TaskListState(items = listOf(plain), pageIndex = 1, hasNext = false, loading = false))
        compose.onNodeWithTag(TAG_TASKS_PREV).assertIsEnabled()
        compose.onNodeWithTag(TAG_TASKS_NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_TASKS_PREV).performClick()
        assertThat(prevs).isEqualTo(1)
    }

    @Test
    fun next_button_pages_forward() {
        show(TaskListState(items = listOf(plain), pageIndex = 0, hasNext = true, loading = false))
        compose.onNodeWithTag(TAG_TASKS_PREV).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_TASKS_NEXT).performClick()
        assertThat(nexts).isEqualTo(1)
    }

    @Test
    fun single_page_hides_the_pager() {
        show(TaskListState(items = listOf(plain), loading = false))
        compose.onNodeWithTag(TAG_TASKS_PREV).assertDoesNotExist()
    }

    @Test
    fun empty_state_text() {
        show(TaskListState(loading = false))
        compose.onNodeWithTag(TAG_TASKS_EMPTY).assertExists()
        compose.onNodeWithText("No tasks match").assertExists()
    }

    @Test
    fun loading_text_not_spinner() {
        show(TaskListState(loading = true))
        compose.onNodeWithText("Loading").assertExists()
        compose.onNodeWithTag(TAG_TASKS_EMPTY).assertDoesNotExist()
    }

    @Test
    fun error_text_is_shown_with_previous_items() {
        show(TaskListState(items = listOf(plain), loading = false, error = "No connection to the server."))
        compose.onNodeWithTag(TAG_TASKS_ERROR).assertExists()
        compose.onNodeWithText("Plain task").assertExists()
    }

    @Test
    fun new_task_button_calls_back() {
        show(TaskListState(loading = false))
        compose.onNodeWithTag(TAG_TASKS_NEW).performClick()
        assertThat(created).isEqualTo(1)
    }

    @Test
    fun all_targets_at_least_48dp() {
        show(
            TaskListState(
                items = listOf(blocked, plain),
                labels = listOf("boox"),
                session = "sess-a",
                pageIndex = 1,
                hasNext = true,
                loading = false,
            ),
        )
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertThat(nodes).isNotEmpty()
        for (i in nodes.indices) compose.onAllNodes(hasClickAction())[i].assertHeightIsAtLeast(48.dp)
    }
}
