package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Update
    suspend fun update(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE state IN ('PENDING', 'SENDING') AND nextAttemptAtMs <= :nowMs ORDER BY id LIMIT 1")
    suspend fun next(nowMs: Long): OutboxEntity?

    @Query("UPDATE outbox SET state = 'PAUSED_AUTH' WHERE state IN ('PENDING', 'SENDING')")
    suspend fun pauseAll()

    @Query("UPDATE outbox SET state = 'PENDING' WHERE state = 'PAUSED_AUTH'")
    suspend fun resumeAll()

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxEntity?

    /**
     * Like [next], but FIFO per conversation: an item waits while an older item of the same
     * conversation is still unsent (pending, sending or paused), so a backing-off upload is
     * never overtaken by a later one that would then open a second "new" conversation.
     */
    @Query(
        "SELECT * FROM outbox AS o WHERE o.state IN ('PENDING', 'SENDING') AND o.nextAttemptAtMs <= :nowMs " +
            "AND NOT EXISTS (SELECT 1 FROM outbox AS e WHERE e.conversationLocalId = o.conversationLocalId " +
            "AND e.id < o.id AND e.state IN ('PENDING', 'SENDING', 'PAUSED_AUTH')) ORDER BY o.id LIMIT 1",
    )
    suspend fun nextInConversationOrder(nowMs: Long): OutboxEntity?

    /** Earliest due time of the items [nextInConversationOrder] could return, null when none is left. */
    @Query(
        "SELECT MIN(o.nextAttemptAtMs) FROM outbox AS o WHERE o.state IN ('PENDING', 'SENDING') " +
            "AND NOT EXISTS (SELECT 1 FROM outbox AS e WHERE e.conversationLocalId = o.conversationLocalId " +
            "AND e.id < o.id AND e.state IN ('PENDING', 'SENDING', 'PAUSED_AUTH'))",
    )
    suspend fun earliestDueMs(): Long?

    @Query("SELECT * FROM outbox WHERE conversationLocalId = :localId")
    suspend fun forConversation(localId: String): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE conversationLocalId = :localId")
    suspend fun deleteForConversation(localId: String)
}
