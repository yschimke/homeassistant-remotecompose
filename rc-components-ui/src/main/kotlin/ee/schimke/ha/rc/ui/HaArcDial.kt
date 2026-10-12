@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaArcDial
import ee.schimke.ha.rc.components.RemoteHaArcDialWide

/**
 * Compose-UI Tier-2 wrapper around the arc dial used by the thermostat, humidifier and light cards.
 * [style] picks [RemoteHaArcDial] or [RemoteHaArcDialWide].
 */
@Composable
fun HaArcDial(
  data: HaArcDialUiData,
  modifier: Modifier = Modifier,
  style: HaArcDialStyle = HaArcDialStyle.Standard,
) {
  HaUiHost(modifier) {
    val remote = data.toRemote()
    when (style) {
      HaArcDialStyle.Standard -> RemoteHaArcDial(remote)
      HaArcDialStyle.Wide -> RemoteHaArcDialWide(remote)
    }
  }
}
