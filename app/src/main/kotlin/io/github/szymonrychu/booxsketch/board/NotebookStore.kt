package io.github.szymonrychu.booxsketch.board

import io.github.szymonrychu.booxsketch.model.Notebook
import kotlinx.serialization.json.Json
import java.io.File

/** Persists the whole [Notebook] as JSON of the model types; writes go through a temp file and a rename. */
class NotebookStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun save(notebook: Notebook) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Notebook.serializer(), notebook))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot replace ${file.name}" }
        }
    }

    /**
     * The saved notebook, or null when nothing was saved or the file is unreadable;
     * an unreadable file is kept as `<name>.corrupt` so the next save cannot destroy it.
     */
    @Synchronized
    fun load(): Notebook? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(Notebook.serializer(), file.readText()) }
            .getOrElse {
                preserveUnreadable()
                null
            }
    }

    /** Removes the notebook and any kept corrupt copy. */
    @Synchronized
    fun delete() {
        file.delete()
        File(file.parentFile, file.name + CORRUPT_SUFFIX).delete()
    }

    private fun preserveUnreadable() {
        val kept = File(file.parentFile, file.name + CORRUPT_SUFFIX)
        kept.delete()
        file.renameTo(kept)
    }

    companion object {
        const val CORRUPT_SUFFIX = ".corrupt"
    }
}
