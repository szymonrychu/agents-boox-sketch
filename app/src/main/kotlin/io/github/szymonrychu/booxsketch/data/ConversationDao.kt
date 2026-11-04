package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE localId = :localId")
    suspend fun get(localId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE deleted = 0 ORDER BY lastActivityMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET serverId = :serverId WHERE localId = :localId")
    suspend fun setServerId(
        localId: String,
        serverId: String,
    )

    @Query("DELETE FROM conversations WHERE localId = :localId")
    suspend fun delete(localId: String)

    @Query("SELECT * FROM conversations WHERE deleted = 0")
    suspend fun all(): List<ConversationEntity>

    /** The most recently active conversation, used when none is selected. */
    @Query("SELECT * FROM conversations WHERE deleted = 0 ORDER BY lastActivityMs DESC LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Query("UPDATE conversations SET title = :title WHERE localId = :localId")
    suspend fun setTitle(
        localId: String,
        title: String,
    )

    @Query("UPDATE conversations SET lastActivityMs = :ms WHERE localId = :localId")
    suspend fun touch(
        localId: String,
        ms: Long,
    )
}
