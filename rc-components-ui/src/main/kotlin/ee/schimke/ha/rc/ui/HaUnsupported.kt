@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaUnsupported

/** Compose-UI Tier-2 wrapper around [RemoteHaUnsupported]. */
@Composable
fun HaUnsupported(data: HaUnsupportedUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaUnsupported(data.toRemote()) }
}
