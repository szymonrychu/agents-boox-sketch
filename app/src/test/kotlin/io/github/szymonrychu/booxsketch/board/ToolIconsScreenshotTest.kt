package io.github.szymonrychu.booxsketch.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.model.PenColor
import io.github.szymonrychu.booxsketch.model.Tool
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h900dp-xhdpi")
class ToolIconsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Sheet() {
        Column(Modifier.background(Color.White)) {
            IconKind.entries.chunked(4).forEach { row ->
                Row {
                    for (kind in row) {
                        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                            ToolIcon(kind, Modifier.size(48.dp), fill = Color(PenColor.RED.argb))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun icon_sheet() {
        compose.setContent { Sheet() }
        compose.onRoot().captureRoboImage("src/test/screenshots/board_tool_icons.png")
    }

    @Test
    fun every_tool_has_an_icon_kind() {
        val kinds = Tool.entries.map { it.iconKind() }
        assertThat(kinds).containsNoDuplicates()
        assertThat(kinds.toSet()).containsAtLeastElementsIn(IconKind.entries.take(6))
    }
}
