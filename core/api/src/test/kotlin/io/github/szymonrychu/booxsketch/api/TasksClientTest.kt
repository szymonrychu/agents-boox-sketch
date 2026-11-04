package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TasksClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TasksClient
    private val bearerHeader = "Bear" + "er test-token"
    private val taskJson = checkNotNull(javaClass.getResource("/tasks/task_full.json")).readText()
    private val emptyJson = checkNotNull(javaClass.getResource("/tasks/task_empty.json")).readText()
    private val listJson = checkNotNull(javaClass.getResource("/tasks/list.json")).readText()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val connector = ConnectorClient(server.url("/").toString().trimEnd('/'), { "test-token" }, OkHttpClient())
        client = TasksClient(connector)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(
        body: String,
        code: Int = 200,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun bodyOf(request: okhttp3.mockwebserver.RecordedRequest): JsonObject =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun list_sends_status_session_repeated_label_cursor_limit() =
        runTest {
            server.enqueue(ok(listJson))
            val page = client.list("active", "alpha-fox", listOf("boox", "ui"), "c-5", 10)
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("GET")
            assertThat(r.requestUrl!!.encodedPath).isEqualTo("/app/v1/sketch/tasks")
            assertThat(r.requestUrl!!.queryParameter("status")).isEqualTo("active")
            assertThat(r.requestUrl!!.queryParameter("session")).isEqualTo("alpha-fox")
            assertThat(r.requestUrl!!.queryParameterValues("label")).containsExactly("boox", "ui").inOrder()
            assertThat(r.requestUrl!!.queryParameter("cursor")).isEqualTo("c-5")
            assertThat(r.requestUrl!!.queryParameter("limit")).isEqualTo("10")
            assertThat(page.items).hasSize(2)
        }

    @Test
    fun list_omits_empty_session_and_cursor() =
        runTest {
            server.enqueue(ok(listJson))
            client.list("all", "", emptyList(), null, 10)
            val url = server.takeRequest().requestUrl!!
            assertThat(url.queryParameter("session")).isNull()
            assertThat(url.queryParameter("cursor")).isNull()
            assertThat(url.queryParameterValues("label")).isEmpty()
        }

    @Test
    fun get_unknown_key_throws_not_found() =
        runTest {
            server.enqueue(ok("""{"error":{"code":"not_found","message":"no task T-9"}}""", 404))
            val e = runCatching { client.get("T-9") }.exceptionOrNull()
            assertThat(e).isEqualTo(ApiError.NotFound("not_found"))
            assertThat(server.takeRequest().path).isEqualTo("/app/v1/sketch/tasks/T-9")
        }

    @Test
    fun create_sends_client_key_and_replay_200_is_ok() =
        runTest {
            server.enqueue(ok(emptyJson, 201))
            server.enqueue(ok(emptyJson, 200))
            val first = client.create("key-1", "task", "Bare task", "desc", listOf("a"))
            val replay = client.create("key-1", "task", "Bare task", "desc", listOf("a"))
            assertThat(first.key).isEqualTo("T-30")
            assertThat(replay.key).isEqualTo("T-30")
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("POST")
            assertThat(r.path).isEqualTo("/app/v1/sketch/tasks")
            val body = bodyOf(r)
            assertThat(body["client_key"]!!.jsonPrimitive.content).isEqualTo("key-1")
            assertThat(body["kind"]!!.jsonPrimitive.content).isEqualTo("task")
            assertThat(body["title"]!!.jsonPrimitive.content).isEqualTo("Bare task")
            assertThat(body["body"]!!.jsonPrimitive.content).isEqualTo("desc")
            assertThat(body["labels"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("a")
        }

    @Test
    fun create_omits_blank_description() =
        runTest {
            server.enqueue(ok(emptyJson, 201))
            client.create("key-1", "idea", "T", null, emptyList())
            assertThat(bodyOf(server.takeRequest()).keys).doesNotContain("body")
        }

    @Test
    fun patch_labels_sends_version_and_labels_only() =
        runTest {
            server.enqueue(ok(taskJson))
            client.patchLabels("T-12", 4, listOf("x", "y"))
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("PATCH")
            assertThat(r.path).isEqualTo("/app/v1/sketch/tasks/T-12")
            val body = bodyOf(r)
            assertThat(body.keys).containsExactly("version", "labels")
            assertThat(body["version"]!!.jsonPrimitive.content).isEqualTo("4")
        }

    @Test
    fun patch_body_sends_version_and_body_only() =
        runTest {
            server.enqueue(ok(taskJson))
            client.patchBody("T-12", 4, "new text")
            val body = bodyOf(server.takeRequest())
            assertThat(body.keys).containsExactly("version", "body")
            assertThat(body["body"]!!.jsonPrimitive.content).isEqualTo("new text")
        }

    @Test
    fun stale_patch_throws_stale_version() =
        runTest {
            server.enqueue(ok("""{"error":{"code":"stale_version","message":"task changed since it was read"}}""", 412))
            val e = runCatching { client.patchBody("T-12", 1, "x") }.exceptionOrNull()
            assertThat(e).isEqualTo(ApiError.StaleVersion)
        }

    @Test
    fun answer_choice_xor_text() =
        runTest {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { client.answer("T-12", 21, 0, "also text", null) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { client.answer("T-12", 21, null, null, null) }
            }
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test
    fun answer_sends_choice_index_or_text_with_note() =
        runTest {
            val decision = """{"id":21,"question":"q","status":"answered","answer":{"choice":1,"label":"No"}}"""
            server.enqueue(ok(decision))
            server.enqueue(ok(decision))
            val answered = client.answer("T-12", 21, 1, null, null)
            assertThat(answered.status).isEqualTo("answered")
            val choice = server.takeRequest()
            assertThat(choice.path).isEqualTo("/app/v1/sketch/tasks/T-12/decisions/21/answer")
            assertThat(bodyOf(choice).keys).containsExactly("choice")
            client.answer("T-12", 21, null, "my own", "because")
            val text = bodyOf(server.takeRequest())
            assertThat(text.keys).containsExactly("text", "note")
        }

    @Test
    fun answer_twice_throws_already_answered() =
        runTest {
            server.enqueue(ok("""{"error":{"code":"already_answered","message":"decision is already answered"}}""", 409))
            val e = runCatching { client.answer("T-12", 21, 0, null, null) }.exceptionOrNull()
            assertThat(e).isEqualTo(ApiError.AlreadyAnswered)
        }

    @Test
    fun todo_status_and_put_bodies() =
        runTest {
            server.enqueue(ok(taskJson))
            server.enqueue(ok(taskJson))
            client.setTodoStatus("T-12", 8, "done")
            val patch = server.takeRequest()
            assertThat(patch.method).isEqualTo("PATCH")
            assertThat(patch.path).isEqualTo("/app/v1/sketch/tasks/T-12/todo/8")
            assertThat(bodyOf(patch).toString()).isEqualTo("""{"status":"done"}""")
            client.putTodo("T-12", listOf(TodoItemDto(7, "Write client", "done"), TodoItemDto(0, "New", "todo")))
            val put = server.takeRequest()
            assertThat(put.method).isEqualTo("PUT")
            assertThat(put.path).isEqualTo("/app/v1/sketch/tasks/T-12/todo")
            val items = bodyOf(put)["items"]!!.jsonArray.map { it.jsonObject }
            assertThat(items.map { it["text"]!!.jsonPrimitive.content }).containsExactly("Write client", "New").inOrder()
            assertThat(items.map { it["status"]!!.jsonPrimitive.content }).containsExactly("done", "todo").inOrder()
            assertThat(items.first().keys).containsExactly("text", "status")
        }

    @Test
    fun comment_posts_body() =
        runTest {
            server.enqueue(ok(taskJson, 201))
            val task = client.comment("T-12", "hello")
            val r = server.takeRequest()
            assertThat(r.method).isEqualTo("POST")
            assertThat(r.path).isEqualTo("/app/v1/sketch/tasks/T-12/comments")
            assertThat(bodyOf(r).toString()).isEqualTo("""{"body":"hello"}""")
            assertThat(task.comments).isNotEmpty()
        }

    @Test
    fun rate_limited_carries_retry_after() =
        runTest {
            server.enqueue(ok("""{"error":"rate_limited","detail":"x"}""", 429).setHeader("Retry-After", "9"))
            val e = runCatching { client.list("active", null, emptyList(), null, 10) }.exceptionOrNull()
            assertThat(e).isEqualTo(ApiError.RateLimited(9L))
        }

    @Test
    fun unavailable_503_maps() =
        runTest {
            server.enqueue(ok("""{"error":"taskboard_unavailable","detail":"x"}""", 503))
            val e = runCatching { client.get("T-1") }.exceptionOrNull()
            assertThat(e).isEqualTo(ApiError.Unavailable("taskboard_unavailable"))
        }

    @Test
    fun unauthorized_maps_to_unauthorized() =
        runTest {
            server.enqueue(ok("""{"error":"unauthorized","detail":"x"}""", 401))
            val e = runCatching { client.get("T-1") }.exceptionOrNull()
            assertThat(e).isEqualTo(ApiError.Unauthorized)
        }

    @Test
    fun every_request_carries_the_bearer_header() =
        runTest {
            repeat(7) { server.enqueue(ok(taskJson)) }
            server.enqueue(ok("""{"id":1,"question":"q","status":"answered"}"""))
            server.enqueue(ok(listJson))
            client.get("T-12")
            client.create("k", "task", "t", null, emptyList())
            client.patchLabels("T-12", 1, emptyList())
            client.patchBody("T-12", 1, "b")
            client.comment("T-12", "c")
            client.putTodo("T-12", emptyList())
            client.setTodoStatus("T-12", 1, "done")
            client.answer("T-12", 1, 0, null, null)
            client.list("all", null, emptyList(), null, 10)
            repeat(server.requestCount) {
                assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo(bearerHeader)
            }
        }

    @Test
    fun client_has_no_status_title_assignee_methods() {
        val names =
            TasksClient::class.java.declaredMethods
                .filter {
                    java.lang.reflect.Modifier
                        .isPublic(it.modifiers)
                }.map { it.name }
        assertThat(names)
            .containsNoneOf(
                "setStatus",
                "patchStatus",
                "setTitle",
                "patchTitle",
                "setAssignee",
                "patchAssignee",
                "patchTodoText",
                "setTodoText",
            )
        assertThat(names.filter { it.contains("status", ignoreCase = true) }).containsExactly("setTodoStatus")
        assertThat(names.filter { it.contains("title", ignoreCase = true) || it.contains("assign", ignoreCase = true) }).isEmpty()
    }
}
