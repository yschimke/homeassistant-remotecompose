@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaHistoryGraph
import ee.schimke.ha.rc.components.RemoteHaSensorCard
import ee.schimke.ha.rc.components.RemoteHaStatisticCard
import ee.schimke.ha.rc.components.RemoteHaStatisticsGraph

/** Compose-UI Tier-2 wrapper around [RemoteHaHistoryGraph]. */
@Composable
fun HaHistoryGraph(data: HaHistoryGraphUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaHistoryGraph(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaStatisticsGraph]. */
@Composable
fun HaStatisticsGraph(data: HaStatisticsGraphUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaStatisticsGraph(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaStatisticCard]. */
@Composable
fun HaStatisticCard(data: HaStatisticCardUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaStatisticCard(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaSensorCard]. */
@Composable
fun HaSensorCard(data: HaSensorCardUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaSensorCard(data.toRemote()) }
}
