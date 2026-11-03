package io.github.szymonrychu.booxsketch.data

import io.github.szymonrychu.booxsketch.api.CursorStore
import io.github.szymonrychu.booxsketch.api.OutboxItem
import io.github.szymonrychu.booxsketch.api.OutboxStorage

/** Room-backed outbox queue and event cursor store. */
class RoomOutboxStorage(
    private val outbox: OutboxDao,
    private val cursors: CursorDao,
) : OutboxStorage,
    CursorStore {
    override suspend fun next(nowMs: Long): OutboxItem? = outbox.next(nowMs)?.toItem()

    override suspend fun update(item: OutboxItem) = outbox.update(item.toEntity())

    override suspend fun pauseAll() = outbox.pauseAll()

    override suspend fun resumeAll() = outbox.resumeAll()

    /** Inserts a new item and returns it with its generated id. */
    suspend fun enqueue(item: OutboxItem): OutboxItem = item.copy(id = outbox.insert(item.toEntity()))

    override suspend fun get(id: String): Long = cursors.get(id) ?: 0L

    override suspend fun set(
        id: String,
        cursor: Long,
    ) = cursors.set(CursorEntity(id, cursor))
}

private fun OutboxEntity.toItem() =
    OutboxItem(
        id,
        clientId,
        kind,
        conversationLocalId,
        conversationServerId,
        conversationMode,
        payloadDir,
        state,
        attempts,
        nextAttemptAtMs,
        lastError,
        serverId,
    )

private fun OutboxItem.toEntity() =
    OutboxEntity(
        id,
        clientId,
        kind,
        conversationLocalId,
        conversationServerId,
        conversationMode,
        payloadDir,
        state,
        attempts,
        nextAttemptAtMs,
        lastError,
        serverId,
    )
