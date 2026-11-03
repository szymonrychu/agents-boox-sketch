package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
}
