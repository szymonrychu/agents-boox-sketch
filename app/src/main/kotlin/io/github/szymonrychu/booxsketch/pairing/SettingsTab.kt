package io.github.szymonrychu.booxsketch.pairing

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.szymonrychu.booxsketch.App

/**
 * Settings panel: the paired origin and Unpair. State comes from the [App] container. Not
 * registered anywhere yet; the tab shell wires it as the "settings" tab.
 */
@Composable
fun SettingsTab(modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as App).container
    var origin by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { origin = container.tokenStore.origin() }
    Box(modifier) { PairingSettings(origin, container::unpair) }
}
