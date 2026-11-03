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
}
