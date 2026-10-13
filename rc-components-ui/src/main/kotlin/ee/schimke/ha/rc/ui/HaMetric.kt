@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.RemoteHaMetric

/** Compose UI preview of a metric block; the same Remote Compose component powers export. */
@Composable
fun HaMetric(
  label: String,
  value: String,
  unit: String = "",
  valueColor: Color = Color(0xFF141414),
  modifier: Modifier = Modifier,
) {
  HaUiHost(modifier) {
    RemoteHaMetric(label = label, value = value, unit = unit, valueColor = valueColor.rc)
  }
}
