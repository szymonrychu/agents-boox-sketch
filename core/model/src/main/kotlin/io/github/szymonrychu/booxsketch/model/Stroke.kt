@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package io.github.szymonrychu.booxsketch.model

/**
 * Drawing surface size in pixels and its density.
 * Task 7 extends this file with Point, Stroke and Tool and drops the suppressions above.
 */
data class Canvas(
    val w: Int,
    val h: Int,
    val dpi: Int,
)
