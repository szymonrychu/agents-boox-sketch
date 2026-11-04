package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedbackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FeedbackEntity>)

    @Query("SELECT MAX(id) FROM feedback WHERE conversationServerId = :serverId")
    suspend fun maxId(serverId: String): Int?

    @Query("SELECT * FROM feedback WHERE conversationServerId = :serverId ORDER BY id")
    suspend fun all(serverId: String): List<FeedbackEntity>

    /** Newest first: the latest feedback is the first row. */
    @Query("SELECT * FROM feedback WHERE conversationServerId = :serverId ORDER BY id DESC")
    fun observe(serverId: String): Flow<List<FeedbackEntity>>

    @Query("DELETE FROM feedback WHERE conversationServerId = :serverId")
    suspend fun deleteFor(serverId: String)
}
