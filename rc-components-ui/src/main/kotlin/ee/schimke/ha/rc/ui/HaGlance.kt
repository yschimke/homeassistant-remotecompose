@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaGlance
import ee.schimke.ha.rc.components.RemoteHaGlanceCell

/** Compose-UI Tier-2 wrapper around [RemoteHaGlance]. */
@Composable
fun HaGlance(data: HaGlanceUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaGlance(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around a single [RemoteHaGlanceCell]. */
@Composable
fun HaGlanceCell(data: HaGlanceCellUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaGlanceCell(data.toRemote()) }
}
