@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaEntities
import ee.schimke.ha.rc.components.RemoteHaEntityRow

/** Compose-UI Tier-2 wrapper around a single [RemoteHaEntityRow]. */
@Composable
fun HaEntityRow(data: HaEntityRowUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaEntityRow(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaEntities]. */
@Composable
fun HaEntities(data: HaEntitiesUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaEntities(data.toRemote()) }
}
