package io.github.szymonrychu.booxsketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A person-or-agent session that has an open decision on a task. */
@Serializable
data class WaitingSessionDto(
    @SerialName("session_id") val sessionId: String,
    val slug: String? = null,
)

/** One todo checklist row. [status] is `todo`, `inprogress` or `done`. */
@Serializable
data class TodoItemDto(
    val id: Long = 0,
    val text: String,
    val status: String = "todo",
    val position: Int = 0,
)

/** One selectable option of a decision. */
@Serializable
data class DecisionOptionDto(
    val label: String,
    val description: String? = null,
    val recommended: Boolean = false,
)

/** The stored answer of a decision: an option index with its label, or free text. */
@Serializable
data class DecisionAnswerDto(
    val choice: Int? = null,
    val label: String? = null,
    val text: String? = null,
)

/** A question an agent asked on a task. [status] is `open`, `answered` or `withdrawn`. */
@Serializable
data class DecisionDto(
    val id: Long,
    @SerialName("task_key") val taskKey: String? = null,
    @SerialName("asking_session") val askingSession: String? = null,
    @SerialName("asking_slug") val askingSlug: String? = null,
    val question: String,
    val context: String = "",
    val options: List<DecisionOptionDto> = emptyList(),
    val status: String,
    val answer: DecisionAnswerDto? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
)

/** One comment; the author is the slug of an agent session or the kind (`user`). */
@Serializable
data class CommentDto(
    val id: Long,
    @SerialName("author_kind") val authorKind: String,
    @SerialName("author_session") val authorSession: String? = null,
    @SerialName("author_slug") val authorSlug: String? = null,
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

/** One history entry: who did what and when. */
@Serializable
data class TaskEventDto(
    val id: Long,
    @SerialName("actor_kind") val actorKind: String,
    @SerialName("actor_session") val actorSession: String? = null,
    @SerialName("actor_slug") val actorSlug: String? = null,
    val kind: String,
    val detail: JsonElement? = null,
    val at: String,
)

/** The task JSON of the taskboard (`GET /tasks/{key}` and the list items). */
@Serializable
data class TaskDto(
    val key: String,
    val title: String,
    val body: String = "",
    val kind: String = "task",
    val status: String,
    val labels: List<String> = emptyList(),
    @SerialName("assignee_session") val assigneeSession: String? = null,
    val version: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val blocked: Boolean = false,
    @SerialName("open_decisions") val openDecisions: Int = 0,
    @SerialName("waiting_sessions") val waitingSessions: List<WaitingSessionDto> = emptyList(),
    val todo: List<TodoItemDto> = emptyList(),
    val decisions: List<DecisionDto> = emptyList(),
    val comments: List<CommentDto> = emptyList(),
    val events: List<TaskEventDto> = emptyList(),
)

/** `GET /tasks`: one page and the cursor of the next, null on the last page. */
@Serializable
data class TaskPageDto(
    val items: List<TaskDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)
