package io.github.szymonrychu.booxsketch.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.api.OutboxItem
import io.github.szymonrychu.booxsketch.api.OutboxKind
import io.github.szymonrychu.booxsketch.api.OutboxState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var storage: RoomOutboxStorage

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        storage = RoomOutboxStorage(db.outboxDao(), db.cursorDao())
    }

    @After
    fun tearDown() = db.close()

    private fun item(
        clientId: String,
        nextAttemptAtMs: Long = 0,
        state: OutboxState = OutboxState.PENDING,
    ) = OutboxItem(
        id = 0,
        clientId = clientId,
        kind = OutboxKind.SKETCH,
        conversationLocalId = "local-1",
        conversationServerId = null,
        conversationMode = null,
        payloadDir = "/payload/$clientId",
        state = state,
        attempts = 0,
        nextAttemptAtMs = nextAttemptAtMs,
        lastError = null,
        serverId = null,
    )

    @Test
    fun outbox_next_respects_next_attempt_at() =
        runBlocking<Unit> {
            val later = storage.enqueue(item("late", nextAttemptAtMs = 5_000))
            val due = storage.enqueue(item("due", nextAttemptAtMs = 1_000))
            assertThat(storage.next(500)).isNull()
            assertThat(storage.next(1_000)?.clientId).isEqualTo("due")
            storage.update(due.copy(state = OutboxState.ACCEPTED))
            assertThat(storage.next(4_999)).isNull()
            assertThat(storage.next(5_000)).isEqualTo(later)
        }

    @Test
    fun pause_and_resume_all() =
        runBlocking<Unit> {
            val a = storage.enqueue(item("a"))
            val b = storage.enqueue(item("b", state = OutboxState.SENDING))
            val done = storage.enqueue(item("c", state = OutboxState.ACCEPTED))
            storage.pauseAll()
            assertThat(storage.next(Long.MAX_VALUE)).isNull()
            assertThat(db.outboxDao().get(a.id)?.state).isEqualTo(OutboxState.PAUSED_AUTH)
            assertThat(db.outboxDao().get(b.id)?.state).isEqualTo(OutboxState.PAUSED_AUTH)
            assertThat(db.outboxDao().get(done.id)?.state).isEqualTo(OutboxState.ACCEPTED)
            storage.resumeAll()
            assertThat(storage.next(0)?.clientId).isEqualTo("a")
            assertThat(db.outboxDao().get(done.id)?.state).isEqualTo(OutboxState.ACCEPTED)
        }

    @Test
    fun cursor_roundtrip() =
        runBlocking<Unit> {
            assertThat(storage.get("srv-1")).isEqualTo(0L)
            storage.set("srv-1", 42)
            storage.set("srv-2", 7)
            storage.set("srv-1", 43)
            assertThat(storage.get("srv-1")).isEqualTo(43L)
            assertThat(storage.get("srv-2")).isEqualTo(7L)
        }

    @Test
    fun latest_spec_query() =
        runBlocking<Unit> {
            val dao = db.specDao()
            assertThat(dao.latest("srv-1")).isNull()
            dao.upsert(SpecEntity("srv-1", 1, "# v1", "2026-01-01T00:00:00Z"))
            dao.upsert(SpecEntity("srv-1", 3, "# v3", "2026-01-03T00:00:00Z"))
            dao.upsert(SpecEntity("srv-1", 2, "# v2", "2026-01-02T00:00:00Z"))
            dao.upsert(SpecEntity("srv-2", 9, "# other", "2026-01-04T00:00:00Z"))
            assertThat(dao.latest("srv-1")?.markdown).isEqualTo("# v3")
            assertThat(dao.all("srv-1").map { it.version }).containsExactly(1, 2, 3).inOrder()
        }

    @Test
    fun deleting_conversation_cascades_thread_items() =
        runBlocking<Unit> {
            val conversations = db.conversationDao()
            conversations.upsert(ConversationEntity("local-1", null, "One", 1, 1))
            conversations.upsert(ConversationEntity("local-2", "srv-2", "Two", 2, 2))
            val threads = db.threadDao()
            threads.upsert(ThreadItemEntity("t1", "local-1", ThreadKind.SKETCH, "c1", null, 2, "/thumbs/t1", "sent", 10))
            threads.upsert(ThreadItemEntity("t2", "local-1", ThreadKind.FEEDBACK, "c2", "looks good", 0, null, "sent", 11))
            threads.upsert(ThreadItemEntity("t3", "local-2", ThreadKind.FEEDBACK, "c3", "other", 0, null, "sent", 12))
            conversations.delete("local-1")
            assertThat(threads.list("local-1")).isEmpty()
            assertThat(threads.list("local-2").map { it.id }).containsExactly("t3")
        }
}
