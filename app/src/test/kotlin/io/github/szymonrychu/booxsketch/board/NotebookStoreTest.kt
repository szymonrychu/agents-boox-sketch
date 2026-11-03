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
}
