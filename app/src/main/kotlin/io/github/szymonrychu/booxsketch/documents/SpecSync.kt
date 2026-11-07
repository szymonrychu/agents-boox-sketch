package io.github.szymonrychu.booxsketch.documents

import androidx.room.withTransaction
import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.SpecEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Copies every spec version of every conversation into Room, once. [latestVersions] maps a server conversation id to
 * its latest spec version (from the last `GET /conversations`). A stored version is never fetched again; a failure on
 * one version stops that conversation only, and an expired token stops the whole run. Nothing is thrown.
 */
class SpecSync(
    private val db: AppDatabase,
    private val client: suspend () -> ConnectorClient?,
    private val latestVersions: suspend () -> Map<String, Int>,
) {
    private val mutex = Mutex()

    /** Returns the number of versions fetched. */
    suspend fun sync(): Int =
        mutex.withLock {
            val c = client() ?: return@withLock 0
            val latest = latestVersions()
            var fetched = 0
            for (conversation in db.conversationDao().all()) {
                val serverId = conversation.serverId
                val top = serverId?.let(latest::get)
                val outcome = if (serverId == null || top == null) Outcome(0, stop = false) else syncConversation(c, serverId, top)
                fetched += outcome.fetched
                if (outcome.stop) break
            }
            fetched
        }

    private class Outcome(
        val fetched: Int,
        val stop: Boolean,
    )

    /** Fetches the missing versions of one conversation; stops at the first failure ([Outcome.stop]: the token is refused). */
    private suspend fun syncConversation(
        c: ConnectorClient,
        serverId: String,
        top: Int,
    ): Outcome {
        val stored = db.specDao().versions(serverId).toSet()
        var fetched = 0
        for (version in (1..top).filter { it !in stored }) {
            when (fetch(c, serverId, version)) {
                FetchResult.Stored -> fetched++
                FetchResult.SkipConversation -> return Outcome(fetched, stop = false)
                FetchResult.Stop -> return Outcome(fetched, stop = true)
            }
        }
        return Outcome(fetched, stop = false)
    }

    private enum class FetchResult { Stored, SkipConversation, Stop }

    private suspend fun fetch(
        c: ConnectorClient,
        serverId: String,
        version: Int,
    ): FetchResult =
        try {
            val dto = c.spec(serverId, version)
            db.withTransaction {
                val layout = dto.layout?.let { Json.encodeToString(it) }
                db.specDao().upsert(
                    SpecEntity(serverId, dto.version, dto.markdown, dto.created, Json.encodeToString(dto.sketchIds), layout),
                )
            }
            FetchResult.Stored
        } catch (_: ApiError.Unauthorized) {
            FetchResult.Stop
        } catch (_: ApiError) {
            FetchResult.SkipConversation
        }
}
