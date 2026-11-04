package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpecDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SpecEntity)

    @Query("SELECT * FROM specs WHERE conversationServerId = :serverId ORDER BY version DESC LIMIT 1")
    suspend fun latest(serverId: String): SpecEntity?

    @Query("SELECT * FROM specs WHERE conversationServerId = :serverId ORDER BY version")
    suspend fun all(serverId: String): List<SpecEntity>

    @Query("DELETE FROM specs WHERE conversationServerId = :serverId")
    suspend fun deleteFor(serverId: String)
}
