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

    /** The highest connector id: local items (negative ids, see [minId]) never count. */
    @Query("SELECT MAX(id) FROM feedback WHERE conversationServerId = :serverId AND id > 0")
    suspend fun maxId(serverId: String): Int?

    /** The lowest id; local items (notes of rejected edit ops) take the next negative one. */
    @Query("SELECT MIN(id) FROM feedback WHERE conversationServerId = :serverId")
    suspend fun minId(serverId: String): Int?

    @Query("SELECT * FROM feedback WHERE conversationServerId = :serverId ORDER BY id")
    suspend fun all(serverId: String): List<FeedbackEntity>

    /** Newest first: the latest feedback is the first row (local items have negative ids, so by time, then id). */
    @Query("SELECT * FROM feedback WHERE conversationServerId = :serverId ORDER BY createdAt DESC, id DESC")
    fun observe(serverId: String): Flow<List<FeedbackEntity>>

    @Query("DELETE FROM feedback WHERE conversationServerId = :serverId")
    suspend fun deleteFor(serverId: String)
}
