package io.github.szymonrychu.booxsketch.board

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.Point
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.InkDiag
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

/** Every committed stroke logs its raw box, redrawn ink bounds, view size and location and the pen parameters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StrokeDiagLineTest {
    private val raw = Stroke("s", Tool.FOUNTAIN, 4f, listOf(Point(100f, 200f, 0.5f, 0), Point(600f, 260f, 0.5f, 16)))

    @Test
    fun the_line_holds_the_raw_box_the_ink_bounds_the_view_and_the_parameters() {
        val line = strokeDiagLine(raw, RectF(88f, 188f, 612f, 272f), 1200, 1600, PointF(56f, 80f), floatArrayOf(0.3f, 0.6f))
        assertThat(line).contains("stroke FOUNTAIN width 4.0 points 2")
        assertThat(line).contains("raw 100,200,600,260 (500x60)")
        assertThat(line).contains("ink 88,188,612,272 (524x84)")
        assertThat(line).contains("view 1200x1600 at 56,80")
        assertThat(line).contains("params [0.3,0.6]")
    }

    @Test
    fun unknown_parameters_and_bounds_say_none() {
        val line = strokeDiagLine(raw.copy(points = emptyList()), null, 10, 10, PointF(), null)
        assertThat(line).contains("raw none ink none")
        assertThat(line).contains("params none")
    }

    @Test
    fun a_committed_stroke_is_logged_under_the_ink_diag_tag() {
        ShadowLog.clear()
        val view =
            BoardSurfaceView(
                androidx.test.core.app.ApplicationProvider
                    .getApplicationContext(),
            )
        view.show(PageCanvas(10, 10, 1f), emptyList(), 0)
        view.committed(raw)
        val logs = ShadowLog.getLogsForTag(InkDiag.TAG)
        assertThat(logs).hasSize(1)
        assertThat(logs.single().msg).contains("raw 100,200,600,260 (500x60)")
    }
}
