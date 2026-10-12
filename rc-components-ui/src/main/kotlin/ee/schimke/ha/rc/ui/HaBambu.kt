@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaBambuAms
import ee.schimke.ha.rc.components.RemoteHaBambuPrintControl
import ee.schimke.ha.rc.components.RemoteHaBambuPrintStatus
import ee.schimke.ha.rc.components.RemoteHaBambuSpool

/** Compose-UI Tier-2 wrapper around [RemoteHaBambuPrintStatus]. */
@Composable
fun HaBambuPrintStatus(data: HaBambuPrintStatusUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaBambuPrintStatus(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaBambuPrintControl]. */
@Composable
fun HaBambuPrintControl(data: HaBambuPrintControlUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaBambuPrintControl(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaBambuAms]. */
@Composable
fun HaBambuAms(data: HaBambuAmsUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaBambuAms(data.toRemote()) }
}

/** Compose-UI Tier-2 wrapper around [RemoteHaBambuSpool]. */
@Composable
fun HaBambuSpool(data: HaBambuSpoolDetailUiData, modifier: Modifier = Modifier) {
  HaUiHost(modifier) { RemoteHaBambuSpool(data.toRemote()) }
}
