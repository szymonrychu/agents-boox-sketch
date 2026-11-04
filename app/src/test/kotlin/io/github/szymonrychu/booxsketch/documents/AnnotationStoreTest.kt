package io.github.szymonrychu.booxsketch.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.conversations.ConversationRepository
import io.github.szymonrychu.booxsketch.data.AppDatabase
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AnnotationStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val layout = DocumentLayout(60, 80)

    private fun store() = AnnotationStore(tmp.root)

    private fun pages(color: Int = Color.WHITE) =
        listOf(0..3, 4..9).map { RenderedPage(Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, it) }

    @Test
    fun freeze_writes_pngs_and_layout_json() {
        val doc = store().freeze("c1", 2, pages(), layout)
        val dir = File(tmp.root, "annotations/c1/v2")
        assertThat(dir.list()!!.toList()).containsAtLeast("page.0.png", "page.1.png", "layout.json")
        assertThat(doc.pages).hasSize(2)
        assertThat(doc.layout).isEqualTo(layout)
    }

    private fun failedPages() = listOf(RenderedPage(Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 0..3, diagramFailed = true))

    @Test
    fun failed_diagram_freeze_is_retried_until_ink_or_send() {
        val s = store()
        s.freeze("c1", 2, failedPages(), layout)
        assertThat(s.needsDiagramRetry("c1", 2)).isTrue()
        s.discardForRetry("c1", 2)
        assertThat(s.frozen("c1", 2)).isNull()
        s.freeze("c1", 2, pages(), layout)
        assertThat(s.needsDiagramRetry("c1", 2)).isFalse()

        s.freeze("c1", 3, failedPages(), layout)
        val inked = Notebook(Canvas(60, 80, 300f), listOf(Page(listOf(stroke()), false)), 0)
        s.notebookStore("c1", 3).save(inked)
        assertThat(s.needsDiagramRetry("c1", 3)).isFalse()
        s.discardForRetry("c1", 3)
        assertThat(s.frozen("c1", 3)).isNotNull()

        s.freeze("c1", 4, failedPages(), layout)
        s.markSent("c1", 4)
        assertThat(s.needsDiagramRetry("c1", 4)).isFalse()
    }

    private fun stroke() = Stroke("a", Tool.BALLPOINT, 4f, listOf(Point(10f, 10f, 0.5f, 0), Point(40f, 40f, 0.5f, 10)))

    @Test
    fun retry_still_fires_after_the_page_view_saved_its_empty_notebook() {
        val s = store()
        s.freeze("c1", 2, failedPages(), layout)
        // The real open sequence: showing the page creates the board, which saves an empty notebook.json.
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val board = DocumentInk(s, scope, Dispatchers.Unconfined).board("c1", 2, 1, Canvas(60, 80, 300f))
        assertThat(File(s.dir("c1", 2), "notebook.json").isFile).isTrue()
        assertThat(s.needsDiagramRetry("c1", 2)).isTrue()
        runBlocking {
            board.onStroke(stroke())
            board.flush()
        }
        assertThat(s.needsDiagramRetry("c1", 2)).isFalse()
        scope.cancel()
    }

    @Test
    fun freeze_twice_keeps_first_pixels() {
        val s = store()
        s.freeze("c1", 1, pages(Color.WHITE), layout)
        val second = s.freeze("c1", 1, pages(Color.RED), layout)
        assertThat(BitmapFactory.decodeFile(second.pages[0].file.path).getPixel(5, 5)).isEqualTo(Color.WHITE)
    }

    @Test
    fun frozen_roundtrip_matches_line_ranges() {
        val s = store()
        s.freeze("c1", 1, pages(), layout)
        val again = AnnotationStore(tmp.root).frozen("c1", 1)!!
        assertThat(again.pages.map { it.sourceLines }).containsExactly(0..3, 4..9).inOrder()
        assertThat(again.layout).isEqualTo(layout)
    }

    @Test
    fun absent_freeze_is_null_and_corrupt_layout_json_reads_as_absent() {
        val s = store()
        assertThat(s.frozen("c1", 1)).isNull()
        s.freeze("c1", 1, pages(), layout)
        File(s.dir("c1", 1), "layout.json").writeText("{ not json")
        assertThat(s.frozen("c1", 1)).isNull()
    }

    @Test
    fun missing_page_file_reads_as_absent() {
        val s = store()
        s.freeze("c1", 1, pages(), layout)
        File(s.dir("c1", 1), "page.1.png").delete()
        assertThat(s.frozen("c1", 1)).isNull()
    }

    @Test
    fun notebook_store_is_per_version() {
        val s = store()
        val nb = Notebook(Canvas(60, 80, 300f), listOf(Page(emptyList(), false)), 0)
        s.notebookStore("c1", 1).save(nb)
        assertThat(s.notebookStore("c1", 1).load()).isEqualTo(nb)
        assertThat(s.notebookStore("c1", 2).load()).isNull()
    }

    @Test
    fun sent_marker_roundtrip() {
        val s = store()
        assertThat(s.isSent("c1", 1)).isFalse()
        s.markSent("c1", 1)
        assertThat(s.isSent("c1", 1)).isTrue()
        assertThat(s.isSent("c1", 2)).isFalse()
    }

    @Test
    fun delete_removes_every_version() {
        val s = store()
        s.freeze("c1", 1, pages(), layout)
        s.freeze("c1", 2, pages(), layout)
        s.freeze("c2", 1, pages(), layout)
        s.delete("c1")
        assertThat(File(tmp.root, "annotations/c1").exists()).isFalse()
        assertThat(s.frozen("c2", 1)).isNotNull()
    }

    @Test
    fun delete_conversation_removes_annotation_dir() =
        runBlocking<Unit> {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
            val s = store()
            val repo =
                ConversationRepository(
                    db,
                    { null },
                    context.getSharedPreferences("annotation-delete", Context.MODE_PRIVATE),
                    tmp.newFolder("outbox"),
                    {},
                    annotations = s,
                )
            db.conversationDao().upsert(ConversationEntity("c1", null, "t", 0, 0))
            s.freeze("c1", 1, pages(), layout)
            assertThat(repo.delete("c1")).isNull()
            assertThat(File(tmp.root, "annotations/c1").exists()).isFalse()
            db.close()
        }
}
