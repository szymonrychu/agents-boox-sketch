package io.github.szymonrychu.booxsketch.board

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotebookStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val notebook =
        Notebook(
            Canvas(100, 200, 300f),
            listOf(
                Page(listOf(Stroke("a", Tool.FOUNTAIN, 3f, listOf(Point(1f, 2f, 0.5f, 7L, 0.1f, null)))), true),
                Page(emptyList(), false),
            ),
            1,
        )

    @Test
    fun round_trips_the_notebook() {
        val store = NotebookStore(File(tmp.root, "nb/notebook.json"))
        store.save(notebook)
        assertThat(store.load()).isEqualTo(notebook)
    }

    @Test
    fun round_trips_the_version_that_consumed_op_strokes() {
        val store = NotebookStore(File(tmp.root, "notebook.json"))
        val page = notebook.pages[0].copy(consumed = setOf("a"), opConsumedAt = mapOf("a" to 3))
        store.save(notebook.copy(pages = listOf(page, notebook.pages[1])))
        assertThat(store.load()!!.pages[0].opConsumedAt).containsExactly("a", 3)
    }

    @Test
    fun load_is_null_without_file_or_with_garbage() {
        val f = File(tmp.root, "notebook.json")
        val store = NotebookStore(f)
        assertThat(store.load()).isNull()
        f.writeText("{not json")
        assertThat(store.load()).isNull()
    }

    @Test
    fun save_overwrites_and_leaves_no_temp_file() {
        val f = File(tmp.root, "notebook.json")
        val store = NotebookStore(f)
        store.save(notebook)
        store.save(notebook.select(0))
        assertThat(store.load()?.current).isEqualTo(0)
        assertThat(tmp.root.list()?.toList()).containsExactly("notebook.json")
    }

    @Test
    fun unreadable_file_is_preserved_as_corrupt() {
        val f = File(tmp.root, "notebook.json")
        f.writeText("{not json")
        assertThat(NotebookStore(f).load()).isNull()
        assertThat(f.exists()).isFalse()
        assertThat(File(tmp.root, "notebook.json.corrupt").readText()).isEqualTo("{not json")
    }

    @Test
    fun old_notebook_without_text_fields_loads() {
        val f = File(tmp.root, "notebook.json")
        f.writeText(
            """{"canvas":{"w":1404,"h":1872,"dpi":300.0},"pages":[{"strokes":[
{"id":"a","tool":"BALLPOINT","width":2.0,"points":[]}],"changedSinceSend":true}],"current":0}""".replace("\n", ""),
        )
        val nb = NotebookStore(f).load()!!
        assertThat(nb.pages[0].strokes.map { it.id }).containsExactly("a")
        assertThat(nb.pages[0].text).isEmpty()
        assertThat(nb.pages[0].consumed).isEmpty()
        assertThat(nb.pages[0].pendingSegments).isEmpty()
        assertThat(nb.pages[0].pendingOps).isEmpty()
        assertThat(nb.pages[0].opConsumedAt).isEmpty()
        assertThat(nb.conversationLocalId).isNull()
    }
}
