@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaPicture
import ee.schimke.ha.rc.components.RemoteHaPictureElements
import ee.schimke.ha.rc.components.RemoteHaPictureEntity
import ee.schimke.ha.rc.components.RemoteHaPictureGlance

/** Compose-UI Tier-2 wrapper around [RemoteHaPicture]. */
@Composable
fun HaPicture(data: HaPictureCardUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaPicture(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaPictureGlance]. */
@Composable
fun HaPictureGlance(data: HaPictureGlanceUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaPictureGlance(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaPictureElements]. */
@Composable
fun HaPictureElements(data: HaPictureElementsUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaPictureElements(data.toRemote()) }
}

/**
 * Compose-UI Tier-2 wrapper around [RemoteHaPictureEntity]. Always draws the icon placeholder — see
 * [HaPictureEntityUiData].
 */
@Composable
fun HaPictureEntity(data: HaPictureEntityUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaPictureEntity(data.toRemote()) }
}
