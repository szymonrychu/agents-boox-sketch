package io.github.szymonrychu.booxsketch.onyx

import com.google.common.truth.Truth.assertThat
import com.onyx.android.sdk.data.note.TouchPoint
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Test

class OnyxStrokeInputTest {
    @Test
    fun raw_points_become_a_stroke_with_relative_time_and_tilt() {
        val pts =
            listOf(
                TouchPoint(10f, 20f, 0.5f, 1f, 30, 0, 1_000L),
                TouchPoint(11f, 21f, 0.6f, 1f, 0, 0, 1_016L),
            )
        val stroke = OnyxStrokeInput.strokeFrom(pts, Tool.MARKER, 6f, "s1")!!
        assertThat(stroke.id).isEqualTo("s1")
        assertThat(stroke.tool).isEqualTo(Tool.MARKER)
        assertThat(stroke.points.map { it.tMs }).containsExactly(0L, 16L).inOrder()
        assertThat(stroke.points[0].tiltX).isEqualTo(30f)
        assertThat(stroke.points[1].tiltX).isNull()
        assertThat(stroke.points[1].pressure).isEqualTo(0.6f)
    }

    @Test
    fun empty_point_list_yields_no_stroke() {
        assertThat(OnyxStrokeInput.strokeFrom(emptyList(), Tool.BALLPOINT, 4f, "s")).isNull()
    }
}
