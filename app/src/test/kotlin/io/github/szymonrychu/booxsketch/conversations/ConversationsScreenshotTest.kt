package io.github.szymonrychu.booxsketch.conversations

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.data.ConversationEntity
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class ConversationsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun list_with_two_conversations() {
        val rows =
            listOf(
                ConversationEntity("a", null, "Settings page sketch", 1_793_872_800_000L, 2),
                ConversationEntity("b", "srv-b", "Login form", 1_793_786_400_000L, 1),
            )
        val utc: (
            Long,
        ) -> String = { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC)) }
        compose.setContent {
            EinkTheme { ConversationsScreen(rows, "b", null, {}, {}, {}, {}, dateText = utc) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/conversations_two.png")
    }
}
