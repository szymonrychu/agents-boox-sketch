package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private class FakeStorage(
    var items: MutableList<OutboxItem>,
) : OutboxStorage {
    val updates = mutableListOf<OutboxItem>()

    override suspend fun next(nowMs: Long): OutboxItem? =
        items.firstOrNull {
            (it.state == OutboxState.PENDING || it.state == OutboxState.SENDING) && it.nextAttemptAtMs <= nowMs
        }

    override suspend fun update(item: OutboxItem) {
        updates += item
        items[items.indexOfFirst { it.id == item.id }] = item
    }

    override suspend fun pauseAll() {
        items =
            items
                .map {
                    if (it.state == OutboxState.PENDING || it.state == OutboxState.SENDING) {
                        it.copy(state = OutboxState.PAUSED_AUTH)
                    } else {
                        it
                    }
                }.toMutableList()
    }

    override suspend fun resumeAll() {
        items =
            items
                .map { if (it.state == OutboxState.PAUSED_AUTH) it.copy(state = OutboxState.PENDING) else it }
                .toMutableList()
    }
}

private fun item(
    id: Long = 1,
    state: OutboxState = OutboxState.PENDING,
    mode: String? = "chat",
) = OutboxItem(
    id = id,
    clientId = "client-$id",
    kind = OutboxKind.SKETCH,
    conversationLocalId = "local",
    conversationServerId = null,
    conversationMode = mode,
    payloadDir = "/p/$id",
    state = state,
    attempts = 0,
    nextAttemptAtMs = 0,
    lastError = null,
    serverId = null,
)

class OutboxPolicyTest {
    private val now = 1_000_000L
    private val noJitter = Backoff(1.seconds, 300.seconds, 0.0)

    private fun core(
        storage: FakeStorage,
        resolve: suspend (OutboxItem) -> OutboxItem = { it },
        send: suspend (OutboxItem) -> String,
    ) = OutboxRunnerCore(storage, send, { now }, noJitter, resolve)

    private suspend fun failWith(
        e: ApiError,
        storage: FakeStorage = FakeStorage(mutableListOf(item())),
    ): Pair<DrainResult, FakeStorage> = core(storage) { throw e }.drainOnce() to storage

    @Test
    fun success_marks_accepted_with_server_id() =
        runTest {
            val s = FakeStorage(mutableListOf(item()))
            val r = core(s) { "srv-1" }.drainOnce()
            assertThat(r).isInstanceOf(DrainResult.Accepted::class.java)
            assertThat(s.items[0].state).isEqualTo(OutboxState.ACCEPTED)
            assertThat(s.items[0].serverId).isEqualTo("srv-1")
        }

    @Test
    fun idle_when_nothing_due() =
        runTest {
            val s = FakeStorage(mutableListOf())
            assertThat(core(s) { "x" }.drainOnce()).isEqualTo(DrainResult.Idle)
        }

    @Test
    fun in_progress_retries_with_backoff() =
        runTest {
            val (r, s) = failWith(ApiError.InProgress)
            assertThat(r).isInstanceOf(DrainResult.WillRetry::class.java)
            assertThat(s.items[0].state).isEqualTo(OutboxState.PENDING)
            assertThat(s.items[0].attempts).isEqualTo(1)
            assertThat(s.items[0].nextAttemptAtMs).isEqualTo(now + 1000)
        }

    @Test
    fun network_retries_and_grows() =
        runTest {
            val s = FakeStorage(mutableListOf(item().copy(attempts = 3)))
            failWith(ApiError.Network(java.io.IOException("down")), s)
            assertThat(s.items[0].state).isEqualTo(OutboxState.PENDING)
            assertThat(s.items[0].nextAttemptAtMs).isEqualTo(now + 8000)
        }

    @Test
    fun server_error_retries() =
        runTest {
            val (_, s) = failWith(ApiError.Server(503))
            assertThat(s.items[0].state).isEqualTo(OutboxState.PENDING)
            assertThat(s.items[0].attempts).isEqualTo(1)
        }

    @Test
    fun retry_after_honoured() =
        runTest {
            val (_, s) = failWith(ApiError.RateLimited(42))
            assertThat(s.items[0].nextAttemptAtMs).isEqualTo(now + 42_000)
            assertThat(s.items[0].state).isEqualTo(OutboxState.PENDING)
        }

    @Test
    fun rate_limited_without_header_uses_backoff() =
        runTest {
            val (_, s) = failWith(ApiError.RateLimited(null))
            assertThat(s.items[0].nextAttemptAtMs).isEqualTo(now + 1000)
        }

    @Test
    fun unauthorized_pauses_all_and_needs_pairing() =
        runTest {
            val s = FakeStorage(mutableListOf(item(1), item(2)))
            val (r, _) = failWith(ApiError.Unauthorized, s)
            assertThat(r).isEqualTo(DrainResult.NeedsPairing)
            assertThat(s.items.map { it.state }).containsExactly(OutboxState.PAUSED_AUTH, OutboxState.PAUSED_AUTH)
            assertThat(s.items.none { it.state == OutboxState.FAILED_PERMANENT }).isTrue()
        }

    @Test
    fun resume_after_pairing_requeues_paused() =
        runTest {
            val s = FakeStorage(mutableListOf(item(1), item(2)))
            val c = core(s) { throw ApiError.Unauthorized }
            c.drainOnce()
            c.resumeAfterPairing()
            assertThat(s.items.map { it.state }).containsExactly(OutboxState.PENDING, OutboxState.PENDING)
        }

    @Test
    fun too_large_fails_permanently() =
        runTest {
            val (r, s) = failWith(ApiError.TooLarge("too_large"))
            assertThat(r).isInstanceOf(DrainResult.Failed::class.java)
            assertThat(s.items[0].state).isEqualTo(OutboxState.FAILED_PERMANENT)
        }

    @Test
    fun unsupported_fails_permanently() =
        runTest {
            assertThat(failWith(ApiError.Unsupported).second.items[0].state).isEqualTo(OutboxState.FAILED_PERMANENT)
        }

    @Test
    fun invalid_fails_permanently() =
        runTest {
            val (_, s) = failWith(ApiError.Invalid("invalid_request", "bad"))
            assertThat(s.items[0].state).isEqualTo(OutboxState.FAILED_PERMANENT)
        }

    @Test
    fun idempotency_mismatch_recorded_as_bug() =
        runTest {
            val (_, s) = failWith(ApiError.Invalid("idempotency_mismatch", "differs"))
            assertThat(s.items[0].state).isEqualTo(OutboxState.FAILED_PERMANENT)
            assertThat(s.items[0].lastError).isEqualTo("bug: idempotency_mismatch")
        }

    @Test
    fun not_found_fails_permanently() =
        runTest {
            val (_, s) = failWith(ApiError.NotFound("unknown_session"))
            assertThat(s.items[0].state).isEqualTo(OutboxState.FAILED_PERMANENT)
        }

    @Test
    fun conflict_no_spec_fails_permanently_with_code() =
        runTest {
            val (r, s) = failWith(ApiError.Conflict("no_spec"))
            assertThat(r).isInstanceOf(DrainResult.Failed::class.java)
            assertThat(s.items[0].state).isEqualTo(OutboxState.FAILED_PERMANENT)
            assertThat(s.items[0].lastError).isEqualTo("conflict: no_spec")
        }

    @Test
    fun sending_row_recovered_with_same_client_id() =
        runTest {
            val s = FakeStorage(mutableListOf(item(state = OutboxState.SENDING)))
            val seen = mutableListOf<String>()
            core(s) {
                seen += it.clientId
                "srv"
            }.drainOnce()
            assertThat(seen).containsExactly("client-1")
            assertThat(s.items[0].state).isEqualTo(OutboxState.ACCEPTED)
        }

    @Test
    fun resolve_called_once_and_persisted_before_send() =
        runTest {
            val s = FakeStorage(mutableListOf(item(mode = null)))
            var resolves = 0
            val resolve: suspend (OutboxItem) -> OutboxItem = {
                resolves++
                it.copy(conversationServerId = "conv-1", conversationMode = "chat")
            }
            var calls = 0
            val c =
                core(s, resolve) {
                    calls++
                    val persisted = s.items[0]
                    assertThat(persisted.state).isEqualTo(OutboxState.SENDING)
                    assertThat(persisted.conversationServerId).isEqualTo("conv-1")
                    if (calls == 1) throw ApiError.Server(500)
                    "srv"
                }
            c.drainOnce()
            assertThat(s.items[0].conversationServerId).isEqualTo("conv-1")
            val later = OutboxRunnerCore(s, { "srv" }, { now + 10_000 }, noJitter, resolve)
            later.drainOnce()
            assertThat(resolves).isEqualTo(1)
            assertThat(s.items[0].state).isEqualTo(OutboxState.ACCEPTED)
        }
}
