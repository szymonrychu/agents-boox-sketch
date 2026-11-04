package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class TaskDetailTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var relay: FakeRelay
    private lateinit var scope: CoroutineScope
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private var back = 0
    private val filtered = mutableListOf<String>()

    private val waiting = """[{"session_id":"sess-a","slug":"alpha-fox"}]"""

    private fun task(version: Int = 3) =
        taskJson(
            "T-12",
            "version" to "$version",
            "body" to "\"Original text\"",
            "labels" to "[\"boox\"]",
            "open_decisions" to "1",
            "waiting_sessions" to waiting,
        )

    @Before
    fun setUp() {
        relay = FakeRelay { json(task()) }
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        relay.shutdown()
    }

    private fun vm(key: String = "T-12") = TaskDetailViewModel(relay.client, key, events, scope, coalesceMs = 100)

    private fun show(vm: TaskDetailViewModel) {
        compose.setContent { EinkTheme { TaskDetailScreen(vm, { back++ }, { filtered += it }) } }
        compose.waitUntil(5_000) { vm.state.value.task != null || vm.state.value.notFound || vm.state.value.error != null }
    }

    private fun bodyOf(i: Int) =
        Json
            .parseToJsonElement(
                relay.requests[i]
                    .body
                    .clone()
                    .readUtf8(),
            ).jsonObject

    private fun await(ok: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!ok()) delay(10) } }

    @Test
    fun not_found_shows_task_not_found_with_back() {
        relay.handler = { FakeRelay.taskboardError(404, "not_found", "no task T-9") }
        show(vm("T-9"))
        compose.onNodeWithText("Task T-9 not found").assertExists()
        compose.onNodeWithTag(TAG_DETAIL_BACK).performClick()
        assertThat(back).isEqualTo(1)
    }

    @Test
    fun header_shows_key_title_status_and_blocked_chip() {
        show(vm())
        compose.onNodeWithText("T-12").assertExists()
        compose.onNodeWithText("Task T-12").assertExists()
        compose.onNodeWithTag(taskStatusTag("T-12")).assertExists()
        compose.onNodeWithText("Blocked (1)").assertExists()
    }

    @Test
    fun stale_patch_reloads_and_shows_exact_banner_text() {
        val vm = vm()
        show(vm)
        relay.handler = { r ->
            if (r.method == "PATCH") {
                FakeRelay.taskboardError(412, "stale_version", "task changed since it was read")
            } else {
                json(task(version = 5))
            }
        }
        vm.saveBody("mine")
        await {
            vm.state.value.staleBanner && vm.state.value.task
                ?.version == 5
        }
        compose.onNodeWithText("The task changed since you opened it; it was reloaded, try again.").assertExists()
        assertThat(STALE_TEXT).isEqualTo("The task changed since you opened it; it was reloaded, try again.")
    }

    @Test
    fun save_body_sends_current_version() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(TAG_OVERVIEW_EDIT).performClick()
        compose.onNodeWithTag(TAG_EDITOR_FIELD).performTextInput(" more")
        compose.onNodeWithTag(TAG_EDITOR_SAVE).performClick()
        await { relay.requests.any { it.method == "PATCH" } }
        val patch = bodyOf(relay.requests.indexOfFirst { it.method == "PATCH" })
        assertThat(patch["version"]!!.jsonPrimitive.content).isEqualTo("3")
        assertThat(patch["body"]!!.jsonPrimitive.content).contains("more")
        assertThat(patch.keys).containsExactly("version", "body")
    }

    @Test
    fun cancel_edit_sends_nothing() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(TAG_OVERVIEW_EDIT).performClick()
        compose.onNodeWithTag(TAG_EDITOR_CANCEL).performClick()
        compose.onNodeWithTag(TAG_OVERVIEW_BODY).assertExists()
        assertThat(relay.requests.none { it.method == "PATCH" }).isTrue()
    }

    @Test
    fun label_edit_sends_version_and_labels() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(TAG_OVERVIEW_LABEL_FIELD).performTextInput("New-One")
        compose.onNodeWithTag(TAG_OVERVIEW_LABEL_ADD).performClick()
        await { relay.requests.any { it.method == "PATCH" } }
        val add = bodyOf(relay.requests.indexOfFirst { it.method == "PATCH" })
        assertThat(add["version"]!!.jsonPrimitive.content).isEqualTo("3")
        assertThat(add["labels"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("boox", "new-one").inOrder()
        await { !vm.state.value.busy }
        compose.onNodeWithTag(overviewLabelTag("boox")).performClick()
        await { relay.requests.count { it.method == "PATCH" } == 2 }
        val remove = bodyOf(relay.requests.indexOfLast { it.method == "PATCH" })
        assertThat(remove["labels"]!!.jsonArray).isEmpty()
    }

    @Test
    fun invalid_label_is_rejected_without_a_request() {
        val vm = vm()
        show(vm)
        compose.onNodeWithTag(TAG_OVERVIEW_LABEL_FIELD).performTextInput("two words")
        compose.onNodeWithTag(TAG_OVERVIEW_LABEL_ADD).performClick()
        compose.onNodeWithTag(TAG_OVERVIEW_LABEL_ERROR).assertExists()
        assertThat(relay.requests.none { it.method == "PATCH" }).isTrue()
    }

    @Test
    fun waiting_session_tap_sets_list_filter_and_goes_back() {
        show(vm())
        compose.onNodeWithTag(waitingSessionTag("sess-a")).performClick()
        assertThat(filtered).containsExactly("sess-a")
        compose.onNodeWithText("alpha-fox").assertExists()
    }

    @Test
    fun no_status_title_or_assignee_controls_exist() {
        show(vm())
        // the only text input on the overview is the add-label field
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        val clickable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val texts =
            clickable.flatMap { n ->
                n.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            }
        assertThat(texts.filter { t -> listOf("status", "title", "assign").any { t.contains(it, ignoreCase = true) } }).isEmpty()
    }

    @Test
    fun live_event_for_this_key_refreshes_once() {
        val vm = vm()
        show(vm)
        val observer = scope.launch { vm.observe() }
        repeat(4) { events.tryEmit("T-12") }
        runBlocking { delay(600) }
        assertThat(relay.requests.count { it.method == "GET" }).isEqualTo(2)
        observer.cancel()
    }

    @Test
    fun event_for_other_key_ignored() {
        val vm = vm()
        show(vm)
        val observer = scope.launch { vm.observe() }
        events.tryEmit("T-99")
        runBlocking { delay(400) }
        assertThat(relay.requests.count { it.method == "GET" }).isEqualTo(1)
        observer.cancel()
    }

    @Test
    fun unauthorized_calls_the_callback() {
        var calls = 0
        relay.handler = { json("""{"error":"unauthorized","detail":"x"}""", 401) }
        val vm = TaskDetailViewModel(relay.client, "T-12", events, scope, onUnauthorized = { calls++ })
        await { vm.state.value.error != null }
        assertThat(calls).isEqualTo(1)
    }
}
