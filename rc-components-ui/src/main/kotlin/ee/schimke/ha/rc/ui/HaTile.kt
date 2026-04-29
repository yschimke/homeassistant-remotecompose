@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaTile

/**
 * Compose-UI Tier-2 wrapper around [RemoteHaTile]. Takes plain
 * Compose / Kotlin types so callers don't have to import any
 * `androidx.compose.remote.*` symbols; internally hosts a
 * RemoteCompose document.
 *
 * Visuals are produced by the same Tier-1 component — any styling
 * change to [RemoteHaTile] lands here automatically.
 */
@Composable
fun HaTile(data: HaTileUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaTile(data.toRemote()) }
}
