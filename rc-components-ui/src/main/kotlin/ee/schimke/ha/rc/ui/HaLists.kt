@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaCalendar
import ee.schimke.ha.rc.components.RemoteHaLogbook
import ee.schimke.ha.rc.components.RemoteHaTodoList

/** Compose-UI Tier-2 wrapper around [RemoteHaLogbook]. */
@Composable
fun HaLogbook(data: HaLogbookUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaLogbook(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaCalendar]. */
@Composable
fun HaCalendar(data: HaCalendarUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaCalendar(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaTodoList]. */
@Composable
fun HaTodoList(data: HaTodoListUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaTodoList(data.toRemote()) }
}
