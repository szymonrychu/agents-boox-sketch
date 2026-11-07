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

/**
 * The board notebooks, one file per conversation (T-46 OQ1 A): `notebook-<localId>.json`, plus `notebook.json` for
 * a notebook not yet bound to a conversation (before the first conversation exists).
 */
class NotebookFiles(
    private val dir: File,
) {
    fun forConversation(localId: String): File = File(dir, "notebook-$localId.json")

    /** The store of [localId]'s notebook; null is the unbound one. */
    fun store(localId: String?): NotebookStore = NotebookStore(localId?.let(::forConversation) ?: File(dir, UNBOUND))

    /** Removes the notebook of [localId] (its conversation is gone). */
    fun delete(localId: String) = store(localId).delete()

    /** Removes every board notebook (Unpair). */
    fun deleteAll() {
        dir
            .listFiles { f ->
                f.name == UNBOUND || f.name.startsWith(UNBOUND + NotebookStore.CORRUPT_SUFFIX) ||
                    f.name.startsWith("notebook-")
            }?.forEach { it.delete() }
    }

    companion object {
        const val UNBOUND = "notebook.json"
    }
}
