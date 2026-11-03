package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val EinkColors =
    lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        secondary = Color.Black,
        onSecondary = Color.White,
        background = Color.White,
        onBackground = Color.Black,
        surface = Color.White,
        onSurface = Color.Black,
        surfaceVariant = Color.White,
        onSurfaceVariant = Color.Black,
        outline = Color.Black,
        secondaryContainer = Color(0xFFDDDDDD),
        onSecondaryContainer = Color.Black,
    )

/** Black on white, no ripples. */
@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EinkColors) {
        CompositionLocalProvider(LocalIndication provides NoIndication, content = content)
    }
}
