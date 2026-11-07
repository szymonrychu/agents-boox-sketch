package io.github.szymonrychu.booxsketch.model.notebook

import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.model.rectOf

/**
 * The image regions of a notebook page (spec 3.6, 4.3 rule 1). A region's members are the visible strokes whose
 * bounding box centre lies inside its rect, the region stroke itself included: they are the picture, sent with the
 * region's image op and never segmented as handwriting.
 */
object ImageRegions {
    private val REFERENCE = Regex("""!\[[^\]]*]\(images/([A-Za-z0-9-]+)\.png\)""")

    /** The region ids [markdown] references as `images/<region id>.png`, in order, each once. */
    fun referencedIds(markdown: String): List<String> =
        REFERENCE
            .findAll(markdown)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /** Every region stroke of [page] that is not consumed: new, sent and waiting, or applied. */
    fun regions(page: Page): List<Stroke> =
        page.strokes.filter { it.tool == Tool.IMAGE_REGION && it.points.size >= 2 && it.id !in page.consumed }

    /** Ids of the visible strokes of [page] inside [region], in page order. */
    fun members(
        page: Page,
        region: Stroke,
    ): List<String> {
        val r = rectOf(region)
        return page.visibleStrokes
            .filter { s ->
                s.points.isNotEmpty() &&
                    (s.points.minOf { it.x } + s.points.maxOf { it.x }) / 2f in r.l..r.r &&
                    (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f in r.t..r.b
            }.map { it.id }
    }

    /** Ids of every stroke inside any region of [page]: kept out of the handwriting segments. */
    fun excluded(page: Page): Set<String> = regions(page).flatMap { members(page, it) }.toSet()

    /** The visible regions of [page] that no image op has carried yet (none waiting for a version). */
    fun unsent(page: Page): List<Stroke> {
        val sent =
            page.pendingOps
                .filter { it.op.kind == OpKind.IMAGE }
                .flatMap { it.op.strokeIds }
                .toSet()
        val visible = page.visibleStrokes.map { it.id }.toSet()
        return regions(page).filter { it.id in visible && it.id !in sent }
    }
}
