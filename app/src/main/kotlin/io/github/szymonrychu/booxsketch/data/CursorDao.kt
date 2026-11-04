package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CursorDao {
    @Query("SELECT cursor FROM cursors WHERE conversationServerId = :serverId")
    suspend fun get(serverId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: CursorEntity)

    @Query("DELETE FROM cursors WHERE conversationServerId = :serverId")
    suspend fun delete(serverId: String)
}
