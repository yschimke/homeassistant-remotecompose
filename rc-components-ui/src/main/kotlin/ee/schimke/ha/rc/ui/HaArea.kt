@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaArea

/** Compose-UI Tier-2 wrapper around [RemoteHaArea]. */
@Composable
fun HaArea(data: HaAreaCardUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaArea(data.toRemote()) }
}
