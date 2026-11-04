package io.github.szymonrychu.booxsketch.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The connector's taskboard relay (`/app/v1/sketch/tasks`). Web parity only: it can read tasks, create one, edit labels
 * and description, comment, change todo rows and answer decisions; status, title, assignee and todo text edits are
 * deliberately absent.
 */
@Suppress("TooManyFunctions") // one method per relay route
class TasksClient(
    private val connector: ConnectorClient,
) {
    /** One page of tasks. [status] is `active`, `done`, `dropped` or `all`; [labels] are ANDed by the server. */
    suspend fun list(
        status: String,
        session: String?,
        labels: List<String>,
        cursor: String?,
        limit: Int,
    ): TaskPageDto {
        val url = "http://relay.invalid/tasks".toHttpUrl().newBuilder()
        url.addQueryParameter("status", status)
        if (!session.isNullOrBlank()) url.addQueryParameter("session", session)
        labels.forEach { url.addQueryParameter("label", it) }
        if (cursor != null) url.addQueryParameter("cursor", cursor)
        url.addQueryParameter("limit", limit.toString())
        val query = url.build().encodedQuery.orEmpty()
        return decode(connector.fetch(connector.authedRequest("/tasks?$query").get().build()))
    }

    suspend fun get(key: String): TaskDto = decode(connector.fetch(connector.authedRequest("/tasks/$key").get().build()))

    /** Creates a task; a replay with the same [clientKey] returns the existing one (HTTP 200). */
    suspend fun create(
        clientKey: String,
        kind: String,
        title: String,
        body: String?,
        labels: List<String>,
    ): TaskDto =
        send(
            "/tasks",
            "POST",
            buildJsonObject {
                put("client_key", clientKey)
                put("kind", kind)
                put("title", title)
                if (!body.isNullOrEmpty()) put("body", body)
                put("labels", strings(labels))
            },
        )

    suspend fun patchLabels(
        key: String,
        version: Int,
        labels: List<String>,
    ): TaskDto =
        send(
            "/tasks/$key",
            "PATCH",
            buildJsonObject {
                put("version", version)
                put("labels", strings(labels))
            },
        )

    suspend fun patchBody(
        key: String,
        version: Int,
        body: String,
    ): TaskDto =
        send(
            "/tasks/$key",
            "PATCH",
            buildJsonObject {
                put("version", version)
                put("body", body)
            },
        )

    /** Adds a comment; the server answers with the whole task. */
    suspend fun comment(
        key: String,
        body: String,
    ): TaskDto = send("/tasks/$key/comments", "POST", buildJsonObject { put("body", body) })

    /** Replaces the whole todo list (add and remove go through this). */
    suspend fun putTodo(
        key: String,
        items: List<TodoItemDto>,
    ): TaskDto =
        send(
            "/tasks/$key/todo",
            "PUT",
            buildJsonObject {
                put(
                    "items",
                    JsonArray(
                        items.map {
                            buildJsonObject {
                                put("text", it.text)
                                put("status", it.status)
                            }
                        },
                    ),
                )
            },
        )

    /** Sets the status (`todo`, `inprogress`, `done`) of one todo row. */
    suspend fun setTodoStatus(
        key: String,
        itemId: Long,
        status: String,
    ): TaskDto = send("/tasks/$key/todo/$itemId", "PATCH", buildJsonObject { put("status", status) })

    /** Answers an open decision with the option index [choice] or the free [text], never both. */
    suspend fun answer(
        key: String,
        decisionId: Long,
        choice: Int?,
        text: String?,
        note: String?,
    ): DecisionDto {
        require((choice == null) != (text == null)) { "answer needs exactly one of choice and text" }
        return send(
            "/tasks/$key/decisions/$decisionId/answer",
            "POST",
            buildJsonObject {
                if (choice != null) put("choice", choice) else put("text", text)
                if (!note.isNullOrBlank()) put("note", note)
            },
        )
    }

    private suspend inline fun <reified T> send(
        path: String,
        method: String,
        body: JsonObject,
    ): T {
        val request =
            connector
                .authedRequest(path)
                .method(method, json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_TYPE))
                .build()
        return decode(connector.fetch(request))
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw ApiError.Network(IOException("unreadable response: ${e.message}", e))
        }

    private fun strings(values: List<String>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}
