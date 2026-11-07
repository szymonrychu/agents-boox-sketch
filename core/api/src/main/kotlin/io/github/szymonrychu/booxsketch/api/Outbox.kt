package io.github.szymonrychu.booxsketch.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class OutboxKind { SKETCH, MESSAGE, HANDOFF }

enum class OutboxState { PENDING, SENDING, ACCEPTED, FAILED_PERMANENT, PAUSED_AUTH }

data class OutboxItem(
    val id: Long,
    val clientId: String,
    val kind: OutboxKind,
    val conversationLocalId: String,
    val conversationServerId: String?,
    val conversationMode: String?,
    val payloadDir: String,
    val state: OutboxState,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val lastError: String?,
    val serverId: String?,
)

interface OutboxStorage {
    /** Oldest item that is PENDING (or SENDING left by a crash) and due at [nowMs]. */
    suspend fun next(nowMs: Long): OutboxItem?

    suspend fun update(item: OutboxItem)

    /** Every PENDING or SENDING item becomes PAUSED_AUTH. */
    suspend fun pauseAll()

    /** Every PAUSED_AUTH item becomes PENDING. */
    suspend fun resumeAll()
}

sealed interface DrainResult {
    data object Idle : DrainResult

    data class Accepted(
        val item: OutboxItem,
    ) : DrainResult

    data class WillRetry(
        val item: OutboxItem,
    ) : DrainResult

    data class Failed(
        val item: OutboxItem,
    ) : DrainResult

    data object NeedsPairing : DrainResult
}

/** Sends at most one outbox item per [drainOnce]; the state machine behind idempotent retry. */
class OutboxRunnerCore(
    private val storage: OutboxStorage,
    private val send: suspend (OutboxItem) -> String,
    private val clock: () -> Long,
    private val backoff: Backoff = Backoff(1.seconds, 300.seconds, 0.2),
    private val resolve: suspend (OutboxItem) -> OutboxItem = { it },
) {
    suspend fun drainOnce(): DrainResult {
        val due = storage.next(clock()) ?: return DrainResult.Idle
        // A SENDING row found here is crash recovery: same client id, treated as pending.
        var item = due.copy(state = OutboxState.SENDING)
        return try {
            if (item.conversationMode == null) {
                item = resolve(item).copy(state = OutboxState.SENDING)
            }
            storage.update(item)
            val serverId = send(item)
            val done = item.copy(state = OutboxState.ACCEPTED, serverId = serverId, lastError = null)
            storage.update(done)
            DrainResult.Accepted(done)
        } catch (e: ApiError) {
            handle(item, e)
        }
    }

    /** Called after re-pairing: paused items become pending again. */
    suspend fun resumeAfterPairing() = storage.resumeAll()

    private suspend fun handle(
        item: OutboxItem,
        e: ApiError,
    ): DrainResult =
        when (e) {
            ApiError.Unauthorized -> {
                storage.update(item.copy(state = OutboxState.PAUSED_AUTH))
                storage.pauseAll()
                DrainResult.NeedsPairing
            }

            ApiError.InProgress, is ApiError.Network, is ApiError.Server -> {
                retry(item, e.message, backoff.delayFor(item.attempts))
            }

            is ApiError.RateLimited -> {
                retry(item, e.message, e.retryAfterS?.seconds ?: backoff.delayFor(item.attempts))
            }

            is ApiError.Conflict -> {
                fail(item, "conflict: ${e.code}")
            }

            is ApiError.Invalid -> {
                fail(item, if (e.code == "idempotency_mismatch") "bug: idempotency_mismatch" else e.message)
            }

            is ApiError.TooLarge, ApiError.Unsupported, ApiError.Purged, ApiError.InvalidCode, is ApiError.NotFound -> {
                fail(item, e.message)
            }

            // notebook errors: Task 4.6 gives the user a way to re-render and re-send; until then the item fails
            ApiError.StaleBase, ApiError.OpReplayed, ApiError.Busy, is ApiError.StaleTarget -> {
                fail(item, e.message)
            }

            // task-only errors: an upload never produces them
            ApiError.StaleVersion, ApiError.AlreadyAnswered, is ApiError.Unavailable -> {
                fail(item, e.message)
            }
        }

    private suspend fun retry(
        item: OutboxItem,
        error: String?,
        delay: Duration,
    ): DrainResult {
        val next =
            item.copy(
                state = OutboxState.PENDING,
                attempts = item.attempts + 1,
                nextAttemptAtMs = clock() + delay.inWholeMilliseconds,
                lastError = error,
            )
        storage.update(next)
        return DrainResult.WillRetry(next)
    }

    private suspend fun fail(
        item: OutboxItem,
        error: String?,
    ): DrainResult {
        val failed = item.copy(state = OutboxState.FAILED_PERMANENT, lastError = error)
        storage.update(failed)
        return DrainResult.Failed(failed)
    }
}
