@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaClock

/**
 * Compose-UI Tier-2 wrapper around [RemoteHaClock]. With [HaClockUiData.staticTimeLabel] null the
 * time ticks inside the embedded document; set it for a frozen capture.
 */
@Composable
fun HaClock(data: HaClockUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaClock(data.toRemote()) }
}
