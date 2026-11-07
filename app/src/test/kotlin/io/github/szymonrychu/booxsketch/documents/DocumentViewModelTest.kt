package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.data.SpecEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocumentViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var store: AnnotationStore
    private var paginated = 0
    private val layout = DocumentLayout(40, 60)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Job() + Dispatchers.Default)
        store = AnnotationStore(tmp.root)
        runBlocking {
            db.conversationDao().upsert(ConversationEntity("c1", "srv-1", "T", 0, 0))
            db.specDao().upsert(SpecEntity("srv-1", 1, "# one", "x"))
            db.specDao().upsert(SpecEntity("srv-1", 2, "# two", "x"))
        }
    }

    @After
    fun tearDown() {
        // Join, not just cancel: a freeze in flight on the IO dispatcher is not interruptible and would otherwise write into
        // the TemporaryFolder after the rule deleted it, leaking an exception into the next test's TestScope.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private fun vm() =
        DocumentViewModel(
            "c1",
            "srv-1",
            db,
            { md ->
                paginated++
                List(3) { RenderedPage(Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888), it..it) }.also { check(md.isNotEmpty()) }
            },
            store,
            layout,
            scope,
        )

    private suspend fun DocumentViewModel.until(ok: (DocumentViewState) -> Boolean) =
        withTimeout(10_000) {
            state.first {
                it.loaded &&
                    ok(it)
            }
        }

    @Test
    fun opens_latest_version_first() =
        runBlocking<Unit> {
            val s = vm().until { true }
            assertThat(s.version).isEqualTo(2)
            assertThat(s.pages).hasSize(3)
            assertThat(s.readOnly).isFalse()
        }

    @Test
    fun older_version_is_read_only() =
        runBlocking<Unit> {
            val vm = vm()
            vm.until { true }
            vm.selectVersion(1)
            val s = vm.until { it.version == 1 }
            assertThat(s.readOnly).isTrue()
            assertThat(s.latestVersion).isEqualTo(2)
        }

    @Test
    fun revert_action_only_on_older_versions() =
        runBlocking<Unit> {
            val reverts = mutableListOf<Pair<Int, Int>>()
            val vm = vm()
            vm.revertTo = { version, latest -> reverts += version to latest }
            vm.until { it.version == 2 }
            assertThat(vm.state.value.canRevert).isFalse()
            vm.revert()
            assertThat(reverts).isEmpty()
            vm.selectVersion(1)
            val s = vm.until { it.version == 1 }
            assertThat(s.canRevert).isTrue()
            vm.revert()
            assertThat(reverts).containsExactly(1 to 2)
        }

    @Test
    fun new_latest_version_makes_open_draft_read_only_for_sending() =
        runBlocking<Unit> {
            val vm = vm()
            vm.until { it.version == 2 }
            db.specDao().upsert(SpecEntity("srv-1", 3, "# three", "x"))
            val s = vm.until { it.latestVersion == 3 }
            assertThat(s.version).isEqualTo(2)
            assertThat(s.readOnly).isTrue()
            assertThat(s.pages).hasSize(3)
        }

    @Test
    fun sent_draft_is_read_only() =
        runBlocking<Unit> {
            val vm = vm()
            vm.until { true }
            store.markSent("c1", 2)
            vm.refreshReadOnly()
            assertThat(vm.state.value.readOnly).isTrue()
        }

    @Test
    fun next_prev_clamp_at_ends() =
        runBlocking<Unit> {
            val vm = vm()
            vm.until { true }
            vm.prev()
            assertThat(vm.state.value.pageIndex).isEqualTo(0)
            repeat(5) { vm.next() }
            assertThat(vm.state.value.pageIndex).isEqualTo(2)
            assertThat(pageCounter(vm.state.value)).isEqualTo("3/3")
        }

    @Test
    fun freeze_is_reused_on_reopen() =
        runBlocking<Unit> {
            vm().until { true }
            val first = paginated
            vm().until { true }
            assertThat(paginated).isEqualTo(first)
        }

    @Test
    fun failed_diagram_freeze_is_paginated_again_on_reopen() =
        runBlocking<Unit> {
            var runs = 0

            fun failing() =
                DocumentViewModel("c1", "srv-1", db, {
                    runs++
                    listOf(RenderedPage(Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888), 0..0, diagramFailed = runs == 1))
                }, store, layout, scope)
            failing().until { true }
            val first = runs
            failing().until { true }
            assertThat(runs).isGreaterThan(first)
            val settled = runs
            failing().until { true }
            assertThat(runs).isEqualTo(settled)
        }

    @Test
    fun empty_spec_opens_with_one_page() =
        runBlocking<Unit> {
            db.conversationDao().upsert(ConversationEntity("c2", "srv-2", "E", 0, 0))
            db.specDao().upsert(SpecEntity("srv-2", 1, "", "x"))
            val vm =
                DocumentViewModel("c2", "srv-2", db, {
                    listOf(RenderedPage(Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888), IntRange.EMPTY))
                }, store, layout, scope)
            assertThat(vm.until { true }.pages).hasSize(1)
        }

    @Test
    fun feedback_follows_the_database_newest_first() =
        runBlocking<Unit> {
            val vm = vm()
            assertThat(withTimeout(10_000) { vm.feedback.first { true } }).isEmpty()
            db.feedbackDao().upsert(FeedbackEntity("srv-1", 1, "note", "one", null, null, "x"))
            db.feedbackDao().upsert(FeedbackEntity("srv-1", 2, "question", "two", "top", 1, "x"))
            db.feedbackDao().upsert(FeedbackEntity("srv-other", 3, "note", "other", null, null, "x"))
            val items = withTimeout(10_000) { vm.feedback.first { it.size == 2 } }
            assertThat(items.map { it.id }).containsExactly(2, 1).inOrder()
        }

    @Test
    fun images_of_the_version_are_fetched_before_paginating() =
        runBlocking<Unit> {
            val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
            val vm =
                DocumentViewModel(
                    "c1",
                    "srv-1",
                    db,
                    { md ->
                        calls += "paginate"
                        List(
                            1,
                        ) { RenderedPage(Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888), it..it) }.also { check(md.isNotEmpty()) }
                    },
                    store,
                    layout,
                    scope,
                    prefetch = { md -> calls += "prefetch:$md" },
                )
            vm.until { true }
            assertThat(calls).containsExactly("prefetch:# two", "paginate").inOrder()
        }
}
