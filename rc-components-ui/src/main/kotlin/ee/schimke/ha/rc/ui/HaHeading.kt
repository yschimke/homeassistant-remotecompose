@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaHeading

/** Compose-UI Tier-2 wrapper around [RemoteHaHeading]. */
@Composable
fun HaHeading(data: HaHeadingUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaHeading(data.toRemote()) }
}
