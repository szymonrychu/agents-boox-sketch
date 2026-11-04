package io.github.szymonrychu.booxsketch.tasks

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.tasks.FakeRelay.Companion.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

class TaskListViewModelTest {
    private lateinit var relay: FakeRelay
    private lateinit var scope: CoroutineScope
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private var unauthorized = 0

    private val pageOne = pageJson(listOf(taskJson("T-3"), taskJson("T-2")), next = "c-2")
    private val pageTwo = pageJson(listOf(taskJson("T-1")), next = null)

    @Before
    fun setUp() {
        relay =
            FakeRelay { r ->
                when (r.requestUrl!!.queryParameter("cursor")) {
                    null -> json(pageOne)
                    "c-2" -> json(pageTwo)
                    else -> FakeRelay.notFound()
                }
            }
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        relay.shutdown()
    }

    private fun vm(coalesceMs: Long = 100) =
        TaskListViewModel(relay.client, events, scope, coalesceMs = coalesceMs, onUnauthorized = {
            unauthorized++
        })

    private fun TaskListViewModel.settled(ok: (TaskListState) -> Boolean = { true }): TaskListState =
        runBlocking { withTimeout(5_000) { state.first { !it.loading && ok(it) } } }

    @Test
    fun defaults_to_active_and_first_page() {
        val state = vm().settled()
        assertThat(state.status).isEqualTo(StatusFilter.ACTIVE)
        assertThat(state.items.map { it.key }).containsExactly("T-3", "T-2").inOrder()
        assertThat(state.pageIndex).isEqualTo(0)
        assertThat(state.hasNext).isTrue()
        val r = relay.requests.single().requestUrl!!
        assertThat(r.queryParameter("status")).isEqualTo("active")
        assertThat(r.queryParameter("limit")).isEqualTo("10")
        assertThat(r.queryParameter("cursor")).isNull()
    }

    @Test
    fun status_change_resets_to_page_one() {
        val vm = vm()
        vm.settled()
        vm.next()
        vm.settled { it.pageIndex == 1 }
        vm.setStatus(StatusFilter.DONE)
        val state = vm.settled { it.status == StatusFilter.DONE }
        assertThat(state.pageIndex).isEqualTo(0)
        val last = relay.requests.last().requestUrl!!
        assertThat(last.queryParameter("status")).isEqualTo("done")
        assertThat(last.queryParameter("cursor")).isNull()
    }

    @Test
    fun session_and_label_filters_are_sent() {
        val vm = vm()
        vm.settled()
        vm.setSession("alpha-fox")
        vm.settled { it.session == "alpha-fox" }
        vm.addLabel(" Boox ")
        vm.addLabel("ui")
        vm.settled { it.labels.size == 2 }
        val url = relay.requests.last().requestUrl!!
        assertThat(url.queryParameter("session")).isEqualTo("alpha-fox")
        assertThat(url.queryParameterValues("label")).containsExactly("boox", "ui").inOrder()
        vm.removeLabel("boox")
        vm.settled { it.labels == listOf("ui") }
        assertThat(
            relay.requests
                .last()
                .requestUrl!!
                .queryParameterValues("label"),
        ).containsExactly("ui")
    }

    @Test
    fun next_prev_walk_the_cursor_stack() {
        val vm = vm()
        vm.settled()
        vm.next()
        val second = vm.settled { it.pageIndex == 1 }
        assertThat(second.items.map { it.key }).containsExactly("T-1")
        assertThat(second.hasNext).isFalse()
        vm.prev()
        val first = vm.settled { it.pageIndex == 0 }
        assertThat(first.items.map { it.key }).containsExactly("T-3", "T-2").inOrder()
        assertThat(
            relay.requests
                .last()
                .requestUrl!!
                .queryParameter("cursor"),
        ).isNull()
        assertThat(relay.requests[1].requestUrl!!.queryParameter("cursor")).isEqualTo("c-2")
    }

    @Test
    fun prev_on_first_page_is_noop() {
        val vm = vm()
        vm.settled()
        vm.prev()
        assertThat(relay.requests).hasSize(1)
        assertThat(vm.state.value.pageIndex).isEqualTo(0)
    }

    @Test
    fun next_without_more_pages_is_noop() {
        val vm = vm()
        vm.settled()
        vm.next()
        vm.settled { it.pageIndex == 1 }
        vm.next()
        assertThat(relay.requests).hasSize(2)
    }

    @Test
    fun refresh_after_change_event_is_coalesced() {
        val vm = vm(coalesceMs = 150)
        vm.settled()
        val observer = scope.launch { vm.observe() }
        repeat(5) { events.tryEmit("T-$it") }
        runBlocking { delay(600) }
        assertThat(relay.requests).hasSize(2)
        observer.cancel()
    }

    @Test
    fun events_are_ignored_when_nobody_observes() {
        val vm = vm()
        vm.settled()
        events.tryEmit("T-1")
        runBlocking { delay(300) }
        assertThat(relay.requests).hasSize(1)
    }

    @Test
    fun unchanged_json_does_not_emit_new_state() {
        val vm = vm(coalesceMs = 50)
        vm.settled()
        val seen = mutableListOf<TaskListState>()
        val collector = scope.launch { vm.state.collect { seen += it } }
        val observer = scope.launch { vm.observe() }
        events.tryEmit("T-1")
        runBlocking { delay(500) }
        assertThat(relay.requests.size).isAtLeast(2)
        assertThat(seen).hasSize(1)
        collector.cancel()
        observer.cancel()
    }

    @Test
    fun changed_json_emits_new_state() {
        val vm = vm(coalesceMs = 50)
        vm.settled()
        relay.handler = { json(pageJson(listOf(taskJson("T-9")), next = null)) }
        val observer = scope.launch { vm.observe() }
        events.tryEmit("T-9")
        val state = vm.settled { it.items.map { t -> t.key } == listOf("T-9") }
        assertThat(state.hasNext).isFalse()
        observer.cancel()
    }

    @Test
    fun `429_sets_retry_after_and_does_not_loop`() {
        var now = 1_000L
        val vm = TaskListViewModel(relay.client, events, scope, clock = { now }, coalesceMs = 50)
        vm.settled()
        relay.handler = { json("""{"error":"rate_limited","detail":"x"}""", 429).setHeader("Retry-After", "30") }
        vm.refresh()
        val limited = vm.settled { it.retryAfterS != null }
        assertThat(limited.retryAfterS).isEqualTo(30L)
        assertThat(limited.error).contains("30")
        val before = relay.requests.size
        val observer = scope.launch { vm.observe() }
        repeat(3) { events.tryEmit("T-1") }
        runBlocking { delay(400) }
        assertThat(relay.requests.size).isEqualTo(before)
        now += 31_000
        events.tryEmit("T-1")
        runBlocking { delay(400) }
        assertThat(relay.requests.size).isEqualTo(before + 1)
        observer.cancel()
    }

    @Test
    fun error_keeps_previous_items() {
        val vm = vm()
        vm.settled()
        relay.handler = { json("""{"error":"taskboard_unavailable","detail":"x"}""", 503) }
        vm.refresh()
        val state = vm.settled { it.error != null }
        assertThat(state.items.map { it.key }).containsExactly("T-3", "T-2").inOrder()
        assertThat(state.error).contains("not reachable")
    }

    @Test
    fun unauthorized_calls_the_callback() {
        relay.handler = { json("""{"error":"unauthorized","detail":"x"}""", 401) }
        val vm = vm()
        vm.settled { it.error != null }
        assertThat(unauthorized).isEqualTo(1)
    }

    @Test
    fun invalid_label_is_rejected_client_side() {
        val vm = vm()
        vm.settled()
        vm.addLabel("two words")
        assertThat(vm.state.value.labelError).isNotNull()
        assertThat(vm.state.value.labels).isEmpty()
        assertThat(relay.requests).hasSize(1)
        vm.addLabel("fine")
        vm.settled { it.labels == listOf("fine") }
        assertThat(vm.state.value.labelError).isNull()
    }
}
