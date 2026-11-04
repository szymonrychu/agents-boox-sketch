package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ThreadItemEntity)

    @Query("SELECT * FROM thread_items WHERE conversationLocalId = :localId ORDER BY createdAtMs, id")
    fun observe(localId: String): Flow<List<ThreadItemEntity>>

    @Query("SELECT * FROM thread_items WHERE conversationLocalId = :localId ORDER BY createdAtMs, id")
    suspend fun list(localId: String): List<ThreadItemEntity>

    /** Marks the thread items of every outbox item paused for a refused token. */
    @Query("UPDATE thread_items SET status = :status WHERE clientId IN (SELECT clientId FROM outbox WHERE state = 'PAUSED_AUTH')")
    suspend fun setStatusForPausedOutbox(status: String)

    @Query("UPDATE thread_items SET status = :to WHERE status = :from")
    suspend fun replaceStatus(
        from: String,
        to: String,
    )

    @Query("UPDATE thread_items SET status = :status WHERE clientId = :clientId")
    suspend fun setStatus(
        clientId: String,
        status: String,
    )

    @Query("UPDATE thread_items SET serverId = :serverId WHERE clientId = :clientId")
    suspend fun setServerId(
        clientId: String,
        serverId: String,
    )

    @Query("SELECT * FROM thread_items WHERE serverId = :serverId LIMIT 1")
    suspend fun byServerId(serverId: String): ThreadItemEntity?
}
