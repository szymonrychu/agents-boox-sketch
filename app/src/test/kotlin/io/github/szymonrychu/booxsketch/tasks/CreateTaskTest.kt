package io.github.szymonrychu.booxsketch.tasks

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
class CreateTaskTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var relay: FakeRelay
    private lateinit var scope: CoroutineScope
    private var created: String? = null

    @Before
    fun setUp() {
        relay = FakeRelay { json(taskJson("T-40"), 201) }
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        relay.shutdown()
    }

    private fun vm(key: String = "key-1") = CreateTaskViewModel(relay.client, scope, { created = it }, clientKey = key)

    private fun awaitRequests(n: Int) = runBlocking { withTimeout(5_000) { while (relay.requests.size < n) kotlinx.coroutines.delay(10) } }

    private fun keyOf(i: Int) =
        Json
            .parseToJsonElement(
                relay.requests[i]
                    .body
                    .clone()
                    .readUtf8(),
            ).jsonObject["client_key"]!!
            .jsonPrimitive.content

    @Test
    fun submit_disabled_for_blank_title() {
        val vm = vm()
        compose.setContent { EinkTheme { CreateTaskScreen(vm, {}) } }
        compose.onNodeWithTag(TAG_CREATE_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_CREATE_TITLE).performTextInput("   ")
        compose.onNodeWithTag(TAG_CREATE_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_CREATE_TITLE).performTextInput("Real")
        compose.onNodeWithTag(TAG_CREATE_SUBMIT).assertIsEnabled()
    }

    @Test
    fun title_over_200_rejected() {
        val vm = vm()
        vm.setTitle("a".repeat(201))
        assertThat(vm.state.value.canSubmit).isFalse()
        compose.setContent { EinkTheme { CreateTaskScreen(vm, {}) } }
        compose.onNodeWithTag(TAG_CREATE_TITLE_ERROR).assertExists()
        vm.submit()
        assertThat(relay.requests).isEmpty()
        vm.setTitle("a".repeat(200))
        assertThat(vm.state.value.canSubmit).isTrue()
    }

    @Test
    fun retry_after_network_error_reuses_client_key() {
        relay.handler = { json("""{"error":"taskboard_unavailable","detail":"x"}""", 503) }
        val vm = vm()
        vm.setTitle("T")
        vm.submit()
        awaitRequests(1)
        runBlocking { withTimeout(5_000) { while (vm.state.value.submitting) kotlinx.coroutines.delay(10) } }
        assertThat(vm.state.value.error).contains("not reachable")
        relay.handler = { json(taskJson("T-40"), 200) }
        vm.submit()
        awaitRequests(2)
        assertThat(keyOf(0)).isEqualTo("key-1")
        assertThat(keyOf(1)).isEqualTo("key-1")
    }

    @Test
    fun double_tap_sends_one_request() {
        val vm = vm()
        vm.setTitle("T")
        vm.submit()
        vm.submit()
        vm.submit()
        awaitRequests(1)
        runBlocking { kotlinx.coroutines.delay(200) }
        assertThat(relay.requests).hasSize(1)
    }

    @Test
    fun success_opens_detail() {
        val vm = vm()
        vm.setTitle("T")
        vm.submit()
        runBlocking { withTimeout(5_000) { while (created == null) kotlinx.coroutines.delay(10) } }
        assertThat(created).isEqualTo("T-40")
    }

    @Test
    fun server_error_message_shown_inline() {
        relay.handler = { FakeRelay.taskboardError(422, "invalid_request", "labels: bad one") }
        val vm = vm()
        compose.setContent { EinkTheme { CreateTaskScreen(vm, {}) } }
        compose.onNodeWithTag(TAG_CREATE_TITLE).performTextInput("T")
        compose.onNodeWithTag(TAG_CREATE_SUBMIT).performClick()
        compose.waitUntil(5_000) { vm.state.value.error != null }
        compose.onNodeWithText("labels: bad one").assertExists()
    }

    @Test
    fun new_form_gets_a_new_client_key() {
        val a = CreateTaskViewModel(relay.client, scope, {})
        val b = CreateTaskViewModel(relay.client, scope, {})
        a.setTitle("A")
        b.setTitle("B")
        a.submit()
        b.submit()
        awaitRequests(2)
        assertThat(keyOf(0)).isNotEqualTo(keyOf(1))
    }

    @Test
    fun labels_follow_the_label_rules() {
        val vm = vm()
        vm.addLabel(" Boox ")
        vm.addLabel("bad label")
        assertThat(vm.state.value.labels).containsExactly("boox")
        assertThat(vm.state.value.labelError).isNotNull()
        vm.removeLabel("boox")
        assertThat(vm.state.value.labels).isEmpty()
    }
}
