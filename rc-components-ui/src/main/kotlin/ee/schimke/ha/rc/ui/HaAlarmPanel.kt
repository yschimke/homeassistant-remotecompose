@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaAlarmPanel
import ee.schimke.ha.rc.components.RemoteHaAlarmPanelWide

/**
 * Compose-UI Tier-2 wrapper around the alarm-panel card. [layout] picks [RemoteHaAlarmPanel] or
 * [RemoteHaAlarmPanelWide]. The status chrome is fixed at [HaAlarmPanelUiData.initialStateInt];
 * Tier-2 has no host value stream to flip it.
 */
@Composable
fun HaAlarmPanel(
  data: HaAlarmPanelUiData,
  modifier: Modifier = Modifier,
  layout: HaCardLayout = HaCardLayout.Standard,
) {
  HaUiHost(modifier) {
    val remote = data.toRemote()
    when (layout) {
      HaCardLayout.Standard -> RemoteHaAlarmPanel(remote)
      HaCardLayout.Wide -> RemoteHaAlarmPanelWide(remote)
    }
  }
}
