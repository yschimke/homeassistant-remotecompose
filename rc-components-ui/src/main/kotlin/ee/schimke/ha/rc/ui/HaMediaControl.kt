@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaMediaControl
import ee.schimke.ha.rc.components.RemoteHaMediaControlWide

/**
 * Compose-UI Tier-2 wrapper around the media-control card. [layout] picks [RemoteHaMediaControl] or
 * [RemoteHaMediaControlWide].
 */
@Composable
fun HaMediaControl(
  data: HaMediaControlUiData,
  modifier: Modifier = Modifier,
  layout: HaCardLayout = HaCardLayout.Standard,
) {
  HaUiHost(modifier) {
    val remote = data.toRemote()
    when (layout) {
      HaCardLayout.Standard -> RemoteHaMediaControl(remote)
      HaCardLayout.Wide -> RemoteHaMediaControlWide(remote)
    }
  }
}
