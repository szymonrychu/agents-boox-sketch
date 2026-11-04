package io.github.szymonrychu.booxsketch.model

import java.util.UUID

/** Outcome of adding a label to a task's label list. */
sealed interface LabelResult {
    data class Added(
        val labels: List<String>,
    ) : LabelResult

    data object Duplicate : LabelResult

    data class Invalid(
        val reason: String,
    ) : LabelResult

    data object TooMany : LabelResult
}

/** Label rules of the taskboard (trim, lowercase, `[a-z0-9_.:-]{1,40}`, at most 20); the server stays the authority. */
object TaskLabels {
    const val MAX_LABELS = 20
    const val MAX_LENGTH = 40
    private val VALID = Regex("[a-z0-9_.:-]{1,$MAX_LENGTH}")

    /** The canonical form of [raw], or null when it is not a valid label. */
    fun normalize(raw: String): String? = raw.trim().lowercase().takeIf { VALID.matches(it) }

    fun add(
        current: List<String>,
        raw: String,
    ): LabelResult {
        val label = normalize(raw)
        return when {
            label == null -> LabelResult.Invalid(reasonFor(raw))
            label in current -> LabelResult.Duplicate
            current.size >= MAX_LABELS -> LabelResult.TooMany
            else -> LabelResult.Added(current + label)
        }
    }

    private fun reasonFor(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> "Label is empty"
            trimmed.length > MAX_LENGTH -> "Label is longer than $MAX_LENGTH characters"
            else -> "Label may only use a-z, 0-9, underscore, dot, colon and dash"
        }
    }
}

/** A fresh idempotency key for a create form. */
fun newClientKey(): String = UUID.randomUUID().toString()
