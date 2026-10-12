@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaGauge
import ee.schimke.ha.rc.components.RemoteHaGaugeStacked
import ee.schimke.ha.rc.components.RemoteHaGaugeWide

/**
 * Compose-UI Tier-2 wrapper around the gauge card. [style] picks the Tier-1 layout:
 * [RemoteHaGauge], [RemoteHaGaugeStacked] or [RemoteHaGaugeWide].
 */
@Composable
fun HaGauge(
  data: HaGaugeUiData,
  modifier: Modifier = Modifier,
  style: HaGaugeStyle = HaGaugeStyle.Standard,
) {
  HaUiHost(modifier) {
    val remote = data.toRemote()
    when (style) {
      HaGaugeStyle.Standard -> RemoteHaGauge(remote)
      HaGaugeStyle.Stacked -> RemoteHaGaugeStacked(remote)
      HaGaugeStyle.Wide -> RemoteHaGaugeWide(remote)
    }
  }
}
