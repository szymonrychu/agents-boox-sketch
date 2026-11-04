package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import io.github.szymonrychu.booxsketch.data.ThreadItemEntity
import io.github.szymonrychu.booxsketch.data.ThreadKind
import io.github.szymonrychu.booxsketch.send.ThreadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DocumentsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var vm: DocumentsViewModel
    private var opened: DocumentRow? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        vm = DocumentsViewModel(db, scope) { opened = it }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun doc(
        local: String,
        activity: Long,
        versions: Int = 1,
        title: String = "t-$local",
    ) {
        db.conversationDao().upsert(ConversationEntity(local, "srv-$local", title, 0, activity))
        for (v in 1..versions) db.specDao().upsert(SpecEntity("srv-$local", v, "# v$v", "2026-11-04T10:0$v:00Z"))
    }

    private suspend fun sketch(
        local: String,
        id: String,
        status: String,
        at: Long,
    ) = db.threadDao().upsert(ThreadItemEntity(id, local, ThreadKind.SKETCH, "c-$id", null, 1, null, status, at))

    /** Room flows run on the query executor: waits until the page satisfies [ok] (the state is not read earlier). */
    private suspend fun settled(ok: (DocumentsPage) -> Boolean = { true }): DocumentsPage =
        withTimeout(5_000) {
            vm.page.first {
                it.loaded &&
                    ok(it)
            }
        }

    private suspend fun rows(count: Int) = settled { it.rows.size == count }.rows

    @Test
    fun conversation_without_spec_is_not_a_document() =
        runBlocking<Unit> {
            doc("a", 1)
            db.conversationDao().upsert(ConversationEntity("b", "srv-b", "no spec", 0, 5))
            db.conversationDao().upsert(ConversationEntity("c", null, "unsent", 0, 6))
            assertThat(rows(1).map { it.conversationLocalId }).containsExactly("a")
        }

    @Test
    fun rows_ordered_by_last_activity() =
        runBlocking<Unit> {
            doc("a", 10)
            doc("b", 30)
            doc("c", 20)
            assertThat(rows(3).map { it.conversationLocalId }).containsExactly("b", "c", "a").inOrder()
        }

    @Test
    fun paging_ten_per_page_prev_next() =
        runBlocking<Unit> {
            for (i in 1..13) doc("d$i", i.toLong())
            val first = settled { it.hasNext }
            assertThat(first.rows).hasSize(10)
            assertThat(first.hasPrev).isFalse()
            vm.next()
            val second = settled { it.index == 1 && it.rows.size == 3 }
            assertThat(second.rows).hasSize(3)
            assertThat(second.hasNext).isFalse()
            vm.next()
            assertThat(settled { it.index == 1 }.index).isEqualTo(1)
            vm.prev()
            assertThat(settled { it.index == 0 }.index).isEqualTo(0)
            vm.prev()
            assertThat(settled { it.index == 0 }.index).isEqualTo(0)
        }

    @Test
    fun state_translating_and_failed_markers() =
        runBlocking<Unit> {
            doc("q", 1)
            doc("f", 2)
            doc("s", 3)
            doc("i", 4)
            sketch("q", "q1", ThreadStatus.QUEUED, at = 100)
            sketch("f", "f1", ThreadStatus.failed("x"), at = 100)
            // Sent long after the latest spec: still translating. Sent before it: idle.
            sketch("s", "s1", ThreadStatus.SENT, at = 4_000_000_000_000L)
            sketch("i", "i1", ThreadStatus.SENT, at = 100)
            val expected = mapOf("q" to DocState.TRANSLATING, "f" to DocState.FAILED, "s" to DocState.TRANSLATING, "i" to DocState.IDLE)
            val byId =
                settled { p ->
                    p.rows.associate { it.conversationLocalId to it.state } == expected
                }.rows.associateBy { it.conversationLocalId }
            assertThat(byId["q"]!!.state).isEqualTo(DocState.TRANSLATING)
            assertThat(byId["f"]!!.state).isEqualTo(DocState.FAILED)
            assertThat(byId["s"]!!.state).isEqualTo(DocState.TRANSLATING)
            assertThat(byId["i"]!!.state).isEqualTo(DocState.IDLE)
        }

    @Test
    fun row_shows_latest_version_and_date() =
        runBlocking<Unit> {
            doc("a", 1, versions = 3)
            val row = settled { p -> p.rows.singleOrNull()?.latestVersion == 3 }.rows.single()
            assertThat(row.latestVersion).isEqualTo(3)
            assertThat(row.latestCreated).isEqualTo("2026-11-04T10:03:00Z")
        }

    @Test
    fun open_reports_the_row() =
        runBlocking<Unit> {
            doc("a", 1)
            vm.open(rows(1).single())
            assertThat(opened?.conversationLocalId).isEqualTo("a")
        }

    @Test
    fun page_reports_loaded_after_first_query() =
        runBlocking<Unit> {
            assertThat(vm.page.first { it.loaded }.rows).isEmpty()
        }
}
