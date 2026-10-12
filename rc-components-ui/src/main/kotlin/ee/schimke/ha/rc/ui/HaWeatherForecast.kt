@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaWeatherForecast
import ee.schimke.ha.rc.components.RemoteHaWeatherForecastWide

/**
 * Compose-UI Tier-2 wrapper around the weather-forecast card. [layout] picks
 * [RemoteHaWeatherForecast] or [RemoteHaWeatherForecastWide].
 */
@Composable
fun HaWeatherForecast(
  data: HaWeatherForecastUiData,
  modifier: Modifier = Modifier,
  layout: HaCardLayout = HaCardLayout.Standard,
) {
  HaUiHost(modifier) {
    val remote = data.toRemote()
    when (layout) {
      HaCardLayout.Standard -> RemoteHaWeatherForecast(remote)
      HaCardLayout.Wide -> RemoteHaWeatherForecastWide(remote)
    }
  }
}
