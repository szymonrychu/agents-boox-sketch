package io.github.szymonrychu.booxsketch.model.notebook

import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Page
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.annotationY
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.arrow
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.g
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.line
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.measure
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.page
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.pen
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.rect
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.run
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.textY
import io.github.szymonrychu.booxsketch.model.notebook.NotebookFixtures.word
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class AnnotationClassifierTest {
    private var ids = 0
    private val classifier = AnnotationClassifier(listOf(ToolInsertMarks, FreehandInsertMarks))

    private fun classify(page: Page) = classifier.classify(page, g, measure) { "op-${++ids}" }

    // "buy fresh milk" on row 4 from x 53: buy 53-113, fresh 133-233, milk 253-333 (centres 83, 183, 293).
    private val milk = run("L1", 4, "buy fresh milk")

    @Test
    fun write_above_words_is_replace_of_those_words() {
        val r = classify(page(listOf(milk), word("w1", 140f, 200f, annotationY(4)), word("w2", 210f, 240f, annotationY(4))))
        val op = r.ops.single()
        assertThat(op.kind).isEqualTo(OpKind.REPLACE)
        assertThat(op.target).isEqualTo(OpTarget("L1", 4, 9, "fresh"))
        assertThat(op.strokeIds).containsExactly("w1", "w2").inOrder()
        assertThat(op.id).isEqualTo("op-1")
        assertThat(r.opStrokeIds).containsExactly("w1", "w2")
        assertThat(r.marginInk).isEmpty()
    }

    @Test
    fun replace_groups_split_at_a_cell_gap() {
        val r = classify(page(listOf(milk), word("w1", 60f, 100f, annotationY(4)), word("w2", 260f, 320f, annotationY(4))))
        assertThat(r.ops.map { it.target }).containsExactly(OpTarget("L1", 0, 3, "buy"), OpTarget("L1", 10, 14, "milk")).inOrder()
    }

    @Test
    fun writing_above_a_row_without_text_is_not_an_op() {
        val r = classify(page(listOf(milk), word("w1", 140f, 200f, annotationY(6)), word("w2", 400f, 500f, annotationY(4))))
        assertThat(r.ops).isEmpty()
        assertThat(r.opStrokeIds).isEmpty()
    }

    @Test
    fun strike_is_delete_of_touched_words() {
        val r = classify(page(listOf(milk), line("s", 140f, 340f, textY(4))))
        val op = r.ops.single()
        assertThat(op.kind).isEqualTo(OpKind.DELETE)
        assertThat(op.target).isEqualTo(OpTarget("L1", 4, 14, "fresh milk"))
        assertThat(op.strokeIds).containsExactly("s")
    }

    @Test
    fun two_strikes_over_the_same_words_are_one_delete() {
        val r = classify(page(listOf(milk), line("s1", 140f, 240f, textY(4)), line("s2", 150f, 230f, textY(4) + 6f)))
        val op = r.ops.single()
        assertThat(op.target).isEqualTo(OpTarget("L1", 4, 9, "fresh"))
        assertThat(op.strokeIds).containsExactly("s1", "s2")
    }

    @Test
    fun margin_box_and_snapped_arrow_is_insert_inline_at_boundary() {
        val box = rect("R", 1000f, 400f, 1300f, 500f)
        val note = word("n1", 1050f, 1200f, 450f)
        val arr = arrow("A", 1000f, 450f, 240f, textY(4))
        val r = classify(page(listOf(milk), box, note, arr))
        val op = r.ops.single()
        assertThat(op.kind).isEqualTo(OpKind.INSERT)
        // 240 lies between the centres of "fresh" (183) and "milk" (293): after "fresh".
        assertThat(op.at).isEqualTo(OpAt("L1", 9, AtMode.INLINE))
        assertThat(op.target).isNull()
        assertThat(op.strokeIds).containsExactly("n1", "R", "A")
        assertThat(op.rect).containsExactly(1000f, 400f, 300f, 100f).inOrder()
        assertThat(r.opStrokeIds).containsExactly("n1", "R", "A")
        assertThat(r.marginInk).isEmpty()
    }

    @Test
    fun arrow_tip_in_annotation_band_is_after_line() {
        val runs = listOf(milk, run("L2", 6, "eggs", sourceLine = 2))
        val r =
            classify(
                page(
                    runs,
                    rect("R", 1000f, 400f, 1300f, 500f),
                    word("n1", 1050f, 1200f, 450f),
                    arrow("A", 1000f, 450f, 100f, annotationY(6)),
                ),
            )
        assertThat(r.ops.single().at).isEqualTo(OpAt("L1", "buy fresh milk".length, AtMode.AFTER_LINE))
    }

    @Test
    fun inline_insert_after_a_marked_up_word_lands_after_its_closing_markup() {
        val r =
            classify(
                page(
                    listOf(run("L1", 4, "**buy** milk")),
                    rect("R", 1000f, 400f, 1300f, 500f),
                    word("n1", 1050f, 1200f, 450f),
                    arrow("A", 1000f, 450f, 120f, textY(4)),
                ),
            )
        assertThat(r.ops.single().at).isEqualTo(OpAt("L1", 7, AtMode.INLINE))
    }

    @Test
    fun after_line_with_two_runs_on_one_row_picks_the_line_start() {
        val runs = listOf(run("L2", 4, "milk", x0 = 500f, sourceLine = 2), run("L1", 4, "buy"))
        val r =
            classify(
                page(
                    runs,
                    rect("R", 1000f, 400f, 1300f, 500f),
                    word("n1", 1050f, 1200f, 450f),
                    arrow("A", 1000f, 450f, 100f, annotationY(6)),
                ),
            )
        assertThat(r.ops.single().at).isEqualTo(OpAt("L1", "buy".length, AtMode.AFTER_LINE))
    }

    // "alpha beta gamma delta" wraps at column 240: row 5 alpha beta, row 6 gamma (53-153) delta (173-273).
    private val wrapped = run("L1", 5, "alpha beta gamma delta").copy(wrapRows = 2, column = 240f)

    @Test
    fun arrow_tip_in_the_second_row_of_a_wrapped_run_is_inline_there() {
        val r =
            classify(
                page(
                    listOf(wrapped),
                    rect("R", 1000f, 400f, 1300f, 500f),
                    word("n1", 1050f, 1200f, 450f),
                    arrow("A", 1000f, 450f, 170f, textY(6)),
                ),
            )
        assertThat(r.ops.single().at).isEqualTo(OpAt("L1", 16, AtMode.INLINE))
    }

    @Test
    fun write_above_a_word_in_the_second_row_of_a_wrapped_run_is_replace_of_that_word() {
        val r = classify(page(listOf(wrapped), word("w1", 180f, 260f, annotationY(6))))
        val op = r.ops.single()
        assertThat(op.kind).isEqualTo(OpKind.REPLACE)
        assertThat(op.target).isEqualTo(OpTarget("L1", 17, 22, "delta"))
    }

    @Test
    fun two_runs_on_one_row_get_separate_replaces() {
        val runs = listOf(run("L1", 4, "buy"), run("L2", 4, "milk", x0 = 500f, sourceLine = 2))
        val r = classify(page(runs, word("w1", 60f, 100f, annotationY(4)), word("w2", 510f, 570f, annotationY(4))))
        assertThat(r.ops.map { it.target }).containsExactly(OpTarget("L1", 0, 3, "buy"), OpTarget("L2", 0, 4, "milk"))
    }

    @Test
    fun arrow_tip_below_the_last_text_is_after_that_line() {
        val r =
            classify(
                page(
                    listOf(milk),
                    rect("R", 1000f, 400f, 1300f, 500f),
                    word("n1", 1050f, 1200f, 450f),
                    arrow("A", 1000f, 500f, 300f, textY(9)),
                ),
            )
        assertThat(r.ops.single().at).isEqualTo(OpAt("L1", 14, AtMode.AFTER_LINE))
    }

    @Test
    fun unsnapped_arrow_is_not_insert() {
        val r =
            classify(
                page(
                    listOf(milk),
                    rect("R", 1000f, 400f, 1300f, 500f),
                    word("n1", 1050f, 1200f, 450f),
                    arrow("A", 1150f, 700f, 240f, textY(4)),
                ),
            )
        assertThat(r.ops).isEmpty()
        assertThat(r.marginInk).containsExactly("R", "n1")
    }

    @Test
    fun margin_ink_without_box_is_margin_ink() {
        val r = classify(page(listOf(milk), word("m1", 1050f, 1200f, 450f)))
        assertThat(r.ops).isEmpty()
        assertThat(r.marginInk).containsExactly("m1")
    }

    @Test
    fun two_ops_on_one_line_in_x_order() {
        // The strike is classified before the replace (rule order), the ops still go in x order.
        val r = classify(page(listOf(milk), line("s", 250f, 340f, textY(4)), word("w", 60f, 100f, annotationY(4))))
        assertThat(r.ops.map { it.kind }).containsExactly(OpKind.REPLACE, OpKind.DELETE).inOrder()
        assertThat(r.ops.map { it.target!!.quote }).containsExactly("buy", "milk").inOrder()
    }

    @Test
    fun quote_is_source_text_with_markup_offsets() {
        val r = classify(page(listOf(run("L1", 4, "**buy** milk")), line("s", 55f, 110f, textY(4))))
        assertThat(r.ops.single().target).isEqualTo(OpTarget("L1", 2, 5, "buy"))
    }

    @Test
    fun rejected_strokes_are_not_classified_again() {
        val p = page(listOf(milk), line("s", 140f, 340f, textY(4))).copy(rejectedStrokes = setOf("s"))
        val r = classify(p)
        assertThat(r.ops).isEmpty()
        assertThat(r.opStrokeIds).isEmpty()
    }

    @Test
    fun strike_bridging_two_strikes_is_one_delete() {
        // buy 53-113, fresh 133-233, milk 253-333, "and" 353-413, "more" 433-513
        val r =
            classify(
                page(
                    listOf(run("L1", 4, "buy fresh milk and more")),
                    line("a", 60f, 230f, textY(4)),
                    line("b", 360f, 500f, textY(4)),
                    line("c", 200f, 380f, textY(4) + 6f),
                ),
            )
        val op = r.ops.single()
        assertThat(op.kind).isEqualTo(OpKind.DELETE)
        assertThat(op.strokeIds).containsExactly("a", "b", "c")
        assertThat(op.target).isEqualTo(OpTarget("L1", 0, 23, "buy fresh milk and more"))
    }

    @Test
    fun pending_and_consumed_strokes_are_not_classified_again() {
        val strike = line("s", 140f, 340f, textY(4))
        val sent = EditOp("old", OpKind.DELETE, OpTarget("L1", 4, 14, "fresh milk"), strokeIds = listOf("s"))
        val p = page(listOf(milk), strike, line("t", 60f, 100f, textY(4)))
        assertThat(classify(p.copy(pendingOps = listOf(PendingOp("c0", sent)))).ops.map { it.strokeIds }).containsExactly(listOf("t"))
        assertThat(classify(p.copy(consumed = setOf("s", "t"))).ops).isEmpty()
    }

    /** A pen circle of radius [r] around ([cx], [cy]), one turn, closing on its start. */
    private fun circle(
        id: String,
        cx: Float,
        cy: Float,
        r: Float,
    ): Stroke {
        val n = 48
        return pen(
            id,
            *(0..n)
                .flatMap {
                    listOf(cx + r * cos(2.0 * PI * it / n).toFloat(), cy + r * sin(2.0 * PI * it / n).toFloat())
                }.toFloatArray(),
        )
    }

    /** A straight pen shaft with a V head of two short arm strokes at its tip: ids "<id>", "<id>-a", "<id>-b". */
    private fun penArrow(
        id: String,
        tailX: Float,
        tailY: Float,
        tipX: Float,
        tipY: Float,
    ): List<Stroke> {
        val n = 20
        val shaft = pen(id, *(0..n).flatMap { listOf(tailX + (tipX - tailX) * it / n, tailY + (tipY - tailY) * it / n) }.toFloatArray())
        val back = atan2(tailY - tipY, tailX - tipX)

        fun arm(
            suffix: String,
            da: Double,
        ) = pen(
            "$id-$suffix",
            tipX,
            tipY,
            tipX + 30f * cos(back + da).toFloat(),
            tipY + 30f * sin(back + da).toFloat(),
        )
        return listOf(shaft, arm("a", 0.45), arm("b", -0.45))
    }

    @Test
    fun freehand_loop_and_arrow_make_insert() {
        val r =
            classify(
                page(
                    listOf(milk),
                    circle("L", 1150f, 450f, 100f),
                    word("n1", 1080f, 1220f, 450f),
                    *penArrow("A", 1050f, 450f, 240f, textY(4)).toTypedArray(),
                ),
            )
        val op = r.ops.single()
        assertThat(op.kind).isEqualTo(OpKind.INSERT)
        assertThat(op.at).isEqualTo(OpAt("L1", 9, AtMode.INLINE))
        assertThat(op.strokeIds).containsExactly("n1", "L", "A", "A-a", "A-b")
        assertThat(r.opStrokeIds).containsExactly("n1", "L", "A", "A-a", "A-b")
        assertThat(r.marginInk).isEmpty()
    }

    @Test
    fun tool_rect_with_freehand_arrow_makes_insert() {
        val r =
            classify(
                page(
                    listOf(milk),
                    rect("R", 1000f, 400f, 1300f, 500f),
                    word("n1", 1050f, 1200f, 450f),
                    *penArrow("A", 1000f, 450f, 240f, textY(4)).toTypedArray(),
                ),
            )
        assertThat(r.ops.single().kind).isEqualTo(OpKind.INSERT)
        assertThat(r.ops.single().strokeIds).containsExactly("n1", "R", "A", "A-a", "A-b")
    }

    @Test
    fun freehand_loop_with_tool_arrow_makes_insert() {
        val r =
            classify(
                page(
                    listOf(milk),
                    circle("L", 1150f, 450f, 100f),
                    word("n1", 1080f, 1220f, 450f),
                    arrow("A", 1050f, 450f, 240f, textY(4)),
                ),
            )
        assertThat(r.ops.single().strokeIds).containsExactly("n1", "L", "A")
    }

    @Test
    fun loop_in_text_column_is_not_insert() {
        val r =
            classify(
                page(
                    listOf(milk),
                    circle("L", 600f, 800f, 100f),
                    word("n1", 530f, 670f, 800f),
                    arrow("A", 500f, 800f, 240f, textY(4)),
                ),
            )
        assertThat(r.ops).isEmpty()
        assertThat(r.opStrokeIds).isEmpty()
    }

    @Test
    fun arrow_tail_far_from_loop_is_not_insert() {
        val r =
            classify(
                page(
                    listOf(milk),
                    circle("L", 1150f, 450f, 100f),
                    word("n1", 1080f, 1220f, 450f),
                    arrow("A", 1050f, 800f, 240f, textY(4)),
                ),
            )
        assertThat(r.ops).isEmpty()
        assertThat(r.opStrokeIds).isEmpty()
    }

    @Test
    fun double_headed_arrow_makes_insert_in_either_drawing_order() {
        // Shaft from the loop to the text, then the head at the loop end, then the head at the text end.
        val tip = penArrow("A", 1050f, 450f, 240f, textY(4))
        val tail = penArrow("T", 240f, textY(4), 1050f, 450f)
        val r =
            classify(
                page(
                    listOf(milk),
                    circle("L", 1150f, 450f, 100f),
                    word("n1", 1080f, 1220f, 450f),
                    tip[0],
                    tail[1],
                    tail[2],
                    tip[1],
                    tip[2],
                ),
            )
        val op = r.ops.single()
        assertThat(op.kind).isEqualTo(OpKind.INSERT)
        assertThat(op.at).isEqualTo(OpAt("L1", 9, AtMode.INLINE))
        assertThat(op.strokeIds).containsExactly("n1", "L", "A", "A-a", "A-b", "T-a", "T-b")
        assertThat(r.marginInk).isEmpty()
    }

    @Test
    fun bare_margin_note_with_arrow_into_text_is_one_note_op() {
        val r =
            classify(
                page(
                    listOf(milk),
                    word("n1", 1050f, 1200f, 450f),
                    *penArrow("A", 1040f, 450f, 400f, textY(4)).toTypedArray(),
                ),
            )
        assertThat(r.ops).isEmpty()
        val note = r.notes.single()
        assertThat(note.kind).isEqualTo(OpKind.NOTE)
        assertThat(note.target).isNull()
        assertThat(note.at).isNull()
        assertThat(note.strokeIds).containsExactly("n1", "A", "A-a", "A-b")
        // The rect bounds the note and its arrow: x 400..1200 from y 442 (the top of the note).
        assertThat(note.rect!!.take(3)).containsExactly(400f, 442f, 800f).inOrder()
        assertThat(r.marginInk).containsExactly("n1")
    }

    @Test
    fun looped_note_is_insert_not_note() {
        val r =
            classify(
                page(
                    listOf(milk),
                    circle("L", 1150f, 450f, 100f),
                    word("n1", 1080f, 1220f, 450f),
                    *penArrow("A", 1050f, 450f, 240f, textY(4)).toTypedArray(),
                ),
            )
        assertThat(r.ops.single().kind).isEqualTo(OpKind.INSERT)
        assertThat(r.notes).isEmpty()
    }

    @Test
    fun margin_clusters_split_by_gap_are_one_note_each_top_first() {
        val r =
            classify(
                page(
                    emptyList(),
                    word("d3", 1000f, 1100f, 1600f),
                    word("d1", 1000f, 1100f, 1200f),
                    word("d2", 1120f, 1250f, 1210f),
                ),
            )
        assertThat(r.ops).isEmpty()
        assertThat(r.notes.map { it.kind }).containsExactly(OpKind.NOTE, OpKind.NOTE)
        assertThat(r.notes.map { it.strokeIds }).containsExactly(listOf("d1", "d2"), listOf("d3")).inOrder()
        assertThat(r.notes[0].rect).containsExactly(1000f, 1192f, 250f, 26f).inOrder()
    }

    @Test
    fun arrow_far_from_any_margin_cluster_is_not_in_a_note() {
        val r =
            classify(
                page(
                    listOf(milk),
                    word("n1", 1050f, 1200f, 450f),
                    arrow("A", 1100f, 900f, 240f, textY(4)),
                ),
            )
        assertThat(r.notes.single().strokeIds).containsExactly("n1")
    }
}
