@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
      fontSize = 12.rsp,
      style = RemoteTextStyle.Default,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    RemoteRow {
      RemoteText(
        text = LiveValues.state(boundEntityId, initialValue),
        color = valueColor ?: theme.primaryText,
        fontSize = 24.rsp,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (boundEntityId == null && unit.isNotBlank()) {
        RemoteText(
          text = unit.rs,
          modifier = RemoteModifier.padding(start = 4.rdp, top = 7.rdp),
          color = theme.secondaryText,
          fontSize = 12.rsp,
          style = RemoteTextStyle.Default,
        )
      }
    }
  }
}
