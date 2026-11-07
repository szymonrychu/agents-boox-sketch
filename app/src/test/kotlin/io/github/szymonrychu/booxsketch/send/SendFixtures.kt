package io.github.szymonrychu.booxsketch.send

import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.notebook.PendingSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

val CANVAS = Canvas(1404, 1872, 300f)

fun stroke(id: String) = Stroke(id, Tool.BALLPOINT, 4f, listOf(Point(10f, 10f, 0.5f, 0), Point(200f, 300f, 0.5f, 16)))

fun page(
    changed: Boolean,
    vararg ids: String,
) = Page(ids.map(::stroke), changed)

/** A board holding [notebook]; [markSent] applies like the view model does. */
class FakeBoard(
    var notebook: Notebook,
) : SendBoard {
    override fun notebook() = notebook

    override suspend fun markSent(sent: Map<Int, Page>) {
        notebook = notebook.markSent(sent.filter { (i, p) -> notebook.pages.getOrNull(i) == p }.keys)
    }

    override suspend fun addPending(pending: Map<Int, List<PendingSegment>>) {
        notebook = notebook.addPending(pending)
    }
}

/** The `meta` form part of a recorded multipart upload. */
fun metaOf(request: RecordedRequest): JsonObject {
    val body = request.body.clone().readUtf8()
    val start = body.indexOf("name=\"meta\"")
    val from = body.indexOf("\r\n\r\n", start) + 4
    val to = body.indexOf("\r\n--", from)
    return Json.parseToJsonElement(body.substring(from, to)).jsonObject
}

/** Names of the file parts of a recorded multipart upload, in order. */
fun partsOf(request: RecordedRequest): List<String> =
    Regex("filename=\"([^\"]+)\"").findAll(request.body.clone().readUtf8()).map { it.groupValues[1] }.toList()

/**
 * A connector double: uploads answer 202 with [conversationId] (or [uploadStatus]), messages and
 * hand-offs 202, config 200. Tracks requests and the peak number in flight at once.
 */
class ConnectorDouble(
    var conversationId: String = "srv-1",
    var uploadStatus: Int = 202,
    private val delayMs: Long = 0,
) : Dispatcher() {
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    private val sketches = AtomicInteger()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val now = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            if (delayMs > 0) Thread.sleep(delayMs)
            val path = request.path.orEmpty()
            if (!path.endsWith("/config")) requests += request
            return when {
                path.endsWith("/config") -> {
                    json(200, CONFIG)
                }

                uploadStatus == 401 -> {
                    json(401, """{"error":"unauthorized","detail":"bad token"}""")
                }

                path.endsWith("/sketches") -> {
                    val n = sketches.incrementAndGet()
                    json(uploadStatus, """{"sketch_id":"sk-$n","conversation_id":"$conversationId","status":"queued"}""")
                }

                path.endsWith("/messages") -> {
                    json(202, """{"message_id":"m-1"}""")
                }

                path.endsWith("/handoff") -> {
                    json(202, """{"handoff_id":"h-1","session_slug":"s"}""")
                }

                path.endsWith("/conversations") && request.method == "POST" -> {
                    json(201, """{"id":"$conversationId"}""")
                }

                else -> {
                    json(404, """{"error":"not_found","detail":""}""")
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        const val CONFIG =
            """{"max_long_edge_px":1568,"max_png_bytes":6291456,"max_strokes_bytes":2097152,"max_pages":4,""" +
                """"max_upload_bytes":8388608,"min_edge_px":200,"api_version":1}"""
    }
}
