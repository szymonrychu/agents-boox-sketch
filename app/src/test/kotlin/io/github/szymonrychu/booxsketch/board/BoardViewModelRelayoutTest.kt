package io.github.szymonrychu.booxsketch.board

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Canvas
import io.github.szymonrychu.booxsketch.model.Notebook
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.notebook.RunPlacer
import io.github.szymonrychu.booxsketch.model.notebook.SourceMap
import io.github.szymonrychu.booxsketch.model.notebook.TextRun
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BoardViewModelRelayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(
        lineId: String,
        row: Int,
        text: String,
        scale: Float,
    ): TextRun {
        val s = SourceMap.stripWithColours(text)
        return TextRun(
            lineId,
            1,
            row,
            row,
            53f,
            scale = scale,
            display = s.display,
            colourSpans = s.spans,
            sourceMap = s.map,
            sourceStrokeIds = emptyList(),
        )
    }

    private fun TestScope.bindable(files: NotebookFiles) =
        BoardViewModel(files.store(null), this, StandardTestDispatcher(testScheduler), files = files)

    @Test
    fun a_saved_notebook_with_shrunk_runs_is_laid_out_at_the_one_text_size_once_on_load() =
        runTest {
            val files = NotebookFiles(tmp.root)
            val long = "word ".repeat(30)
            val old =
                Notebook(
                    Canvas(1404, 1872, 300f),
                    listOf(Page(emptyList(), false, text = listOf(run("L1", 2, long, 0.85f), run("L2", 3, "short", 1f)))),
                    0,
                )
            files.store("A").save(old)
            val v = bindable(files)
            v.bindConversation("A")
            val runs =
                v.notebook.value.pages[0]
                    .text
            assertThat(runs.map { it.scale }.toSet()).containsExactly(RunPlacer.TEXT_SCALE)
            val l1 = runs.single { it.lineId == "L1" }
            assertThat(l1.wrapRows).isAtLeast(3)
            assertThat(runs.single { it.lineId == "L2" }.row).isEqualTo(l1.row + l1.wrapRows)
            // Persisted: the file on disk no longer holds the old scale.
            assertThat(
                files
                    .store("A")
                    .load()!!
                    .pages[0]
                    .text
                    .map { it.scale }
                    .toSet(),
            ).containsExactly(RunPlacer.TEXT_SCALE)
        }
}
