@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow

/** A small metric block for assembling sensor cards and widget summaries. */
@Composable
@RemoteComposable
fun RemoteHaMetric(
  label: String,
  value: String,
  unit: String = "",
  entityId: String? = null,
  valueColor: RemoteColor? = null,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  val boundEntityId = entityId?.takeIf { it.isNotBlank() }
  // The host pushes formatState(entity) into <entityId>.state, including its unit. Seed that same
  // shape and do not draw a second unit next to a bound value after the first live update.
  val initialValue =
    if (boundEntityId != null && unit.isNotBlank() && value.trim().toDoubleOrNull() != null) {
      "$value $unit"
    } else {
      value
    }
  RemoteColumn(modifier = modifier.padding(horizontal = 8.rdp, vertical = 6.rdp)) {
    RemoteText(
      text = entityName(entityId, label),
      color = theme.secondaryText,
      fontSize = HaType.Label,
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
    )
    RemoteRow {
      RemoteText(
        text = LiveValues.state(boundEntityId, initialValue),
        color = valueColor ?: theme.primaryText,
        fontSize = HaType.Value,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      if (boundEntityId == null && unit.isNotBlank()) {
        RemoteText(
          text = unit.rs,
          modifier = RemoteModifier.padding(start = 4.rdp, top = 7.rdp),
          color = theme.secondaryText,
          fontSize = HaType.Label,
          style = haTextStyle(),
        )
      }
    }
  }
}
