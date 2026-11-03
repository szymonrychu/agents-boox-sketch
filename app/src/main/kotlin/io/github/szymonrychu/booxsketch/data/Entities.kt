package io.github.szymonrychu.booxsketch.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState

enum class ThreadKind { SKETCH, FEEDBACK }

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String,
    val kind: OutboxKind,
    val conversationLocalId: String,
    val conversationServerId: String?,
    val conversationMode: String?,
    val payloadDir: String,
    val state: OutboxState,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val lastError: String?,
    val serverId: String?,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val title: String,
    val createdAtMs: Long,
    val lastActivityMs: Long,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "thread_items",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["localId"],
            childColumns = ["conversationLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationLocalId")],
)
data class ThreadItemEntity(
    @PrimaryKey val id: String,
    val conversationLocalId: String,
    val kind: ThreadKind,
    val clientId: String,
    val text: String?,
    val pageCount: Int,
    val thumbDir: String?,
    val status: String,
    val createdAtMs: Long,
)

@Entity(tableName = "specs", primaryKeys = ["conversationServerId", "version"])
data class SpecEntity(
    val conversationServerId: String,
    val version: Int,
    val markdown: String,
    val createdAt: String,
)

@Entity(tableName = "cursors")
data class CursorEntity(
    @PrimaryKey val conversationServerId: String,
    val cursor: Long,
)
