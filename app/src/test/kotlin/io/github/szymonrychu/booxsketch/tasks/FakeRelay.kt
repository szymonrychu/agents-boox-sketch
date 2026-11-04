package io.github.szymonrychu.booxsketch.tasks

import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.api.TasksClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections

/** A fake taskboard relay: [handler] answers every request; the requests are recorded in order. */
class FakeRelay(
    var handler: (RecordedRequest) -> MockResponse = { notFound() },
) {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    val client: TasksClient

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return handler(request)
                }
            }
        server.start()
        client = TasksClient(ConnectorClient(server.url("/").toString().trimEnd('/'), { "test-token" }, OkHttpClient()))
    }

    fun count(
        method: String,
        pathPrefix: String,
    ) = requests.count { it.method == method && it.path!!.startsWith("/app/v1/sketch$pathPrefix") }

    fun shutdown() = server.shutdown()

    companion object {
        fun json(
            body: String,
            code: Int = 200,
        ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

        fun notFound() = json("""{"error":{"code":"not_found","message":"no such route"}}""", 404)

        fun taskboardError(
            status: Int,
            code: String,
            message: String = "m",
        ) = json("""{"error":{"code":"$code","message":"$message"}}""", status)
    }
}

/**
 * Compact task JSON for tests. [fields] override or add top-level fields; values are raw JSON, for example
 * `"status" to "\"done\""`.
 */
fun taskJson(
    key: String,
    vararg fields: Pair<String, String>,
): String {
    val all =
        linkedMapOf(
            "key" to "\"$key\"",
            "title" to "\"Task $key\"",
            "body" to "\"\"",
            "kind" to "\"task\"",
            "status" to "\"open\"",
            "labels" to "[]",
            "assignee_session" to "null",
            "version" to "1",
            "blocked" to "false",
            "open_decisions" to "0",
        )
    fields.forEach { all[it.first] = it.second }
    return all.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
}

fun pageJson(
    items: List<String>,
    next: String? = null,
): String = """{"items":[${items.joinToString(",")}],"next_cursor":${if (next == null) "null" else "\"$next\""}}"""
