package io.github.szymonrychu.booxsketch.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A conversation with at least one stored spec version: the raw row of the Documents list. */
data class DocumentRaw(
    val conversationLocalId: String,
    val serverId: String,
    val title: String,
    val lastActivityMs: Long,
    val latestVersion: Int,
    val latestCreated: String,
)

@Dao
interface DocumentDao {
    /** Conversations with a spec, newest activity first, each with its latest spec version. */
    @Query(
        """
        SELECT c.localId AS conversationLocalId, c.serverId AS serverId, c.title AS title, c.lastActivityMs AS lastActivityMs,
               s.version AS latestVersion, s.createdAt AS latestCreated
        FROM conversations c JOIN specs s ON s.conversationServerId = c.serverId
        WHERE c.deleted = 0
          AND s.version = (SELECT MAX(version) FROM specs WHERE conversationServerId = c.serverId)
        ORDER BY c.lastActivityMs DESC, c.localId
        """,
    )
    fun observeDocuments(): Flow<List<DocumentRaw>>

    /** Every sketch thread item, for the Documents list markers. */
    @Query("SELECT * FROM thread_items WHERE kind = 'SKETCH' ORDER BY createdAtMs, id")
    fun observeSketches(): Flow<List<ThreadItemEntity>>
}
