package io.github.szymonrychu.booxsketch.pairing

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class PairingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(
        name: String,
        origin: String = "",
        code: String = "",
        error: PairResult? = null,
    ) {
        compose.setContent {
            EinkTheme {
                PairingContent(origin, code, error?.let(::pairingErrorText), false, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun empty() = shoot("pairing_empty")

    @Test
    fun partial_code() = shoot("pairing_partial_code", "connector.example", "123")

    @Test
    fun full_code() = shoot("pairing_full_code", "https://connector.example", "123456")

    @Test
    fun invalid_origin() = shoot("pairing_invalid_origin", "http://connector.example", "123456", PairResult.InvalidOrigin)

    @Test
    fun code_rejected() = shoot("pairing_code_rejected", "https://connector.example", "", PairResult.CodeRejected)

    @Test
    fun rate_limited() = shoot("pairing_rate_limited", "https://connector.example", "", PairResult.RateLimited(30))

    @Test
    fun unreachable() = shoot("pairing_unreachable", "https://connector.example", "123456", PairResult.Unreachable("timeout"))

    @Test
    fun settings() {
        compose.setContent { EinkTheme { PairingSettings("https://connector.example") {} } }
        compose.onRoot().captureRoboImage("src/test/screenshots/pairing_settings.png")
    }
}
