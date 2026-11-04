package io.github.szymonrychu.booxsketch.documents

import io.github.szymonrychu.booxsketch.api.ApiError
import io.github.szymonrychu.booxsketch.api.ConnectorClient
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Copies the translator's feedback of every conversation into Room. [latestIds] maps a server conversation id to its
 * `latest_feedback_id` (from the last `GET /conversations`); a conversation is fetched only when that id is above the
 * highest stored one, page by page from the stored maximum. A failure stops that conversation only, an expired token
 * stops the whole run, and a connector that sends no feedback ids is left alone. Nothing is thrown.
 */
class FeedbackSync(
    private val db: AppDatabase,
    private val client: suspend () -> ConnectorClient?,
    private val latestIds: suspend () -> Map<String, Int>,
) {
    private val mutex = Mutex()

    /** Returns the number of items fetched. */
    suspend fun sync(): Int =
        mutex.withLock {
            val c = client() ?: return@withLock 0
            val latest = latestIds()
            var fetched = 0
            for (conversation in db.conversationDao().all()) {
                val serverId = conversation.serverId ?: continue
                val top = latest[serverId] ?: continue
                val outcome = syncConversation(c, serverId, top)
                fetched += outcome.fetched
                if (outcome.stop) break
            }
            fetched
        }

    private class Outcome(
        val fetched: Int,
        val stop: Boolean,
    )

    private suspend fun syncConversation(
        c: ConnectorClient,
        serverId: String,
        top: Int,
    ): Outcome {
        var have = db.feedbackDao().maxId(serverId) ?: 0
        var fetched = 0
        try {
            while (have < top) {
                val page = c.feedback(serverId, have)
                if (page.items.isEmpty()) break
                db.feedbackDao().upsertAll(
                    page.items.map { FeedbackEntity(serverId, it.id, it.kind, it.text, it.where, it.specVersion, it.createdAt) },
                )
                fetched += page.items.size
                have = page.items.maxOf { it.id }
            }
        } catch (_: ApiError.Unauthorized) {
            return Outcome(fetched, stop = true)
        } catch (_: ApiError) {
            return Outcome(fetched, stop = false)
        }
        return Outcome(fetched, stop = false)
    }
}
