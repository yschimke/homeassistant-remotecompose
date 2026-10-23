@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * `statistic` card — single hero value (mean / min / max / sum) for one entity, with optional
 * period label + unit suffix. [compact] sets the name in caption type and the value a size down,
 * and leaves out the period: two lines that fit a Wear Small slot.
 */
@Composable
@RemoteComposable
fun RemoteHaStatisticCard(
  data: HaStatisticCardData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = (if (compact) 4 else 12).rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy((if (compact) 0 else 4).rdp)) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = if (compact) HaType.Caption else HaType.Label,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteRow(verticalAlignment = RemoteAlignment.Bottom) {
        RemoteText(
          text = data.valueLabel,
          color = theme.primaryText,
          fontSize = if (compact) HaType.ValueSmall else HaType.Display,
          fontWeight = haValueWeight(),
          style = haTextStyle(),
          maxLines = 1,
        )
        if (data.unit != null) {
          RemoteBox(
            modifier = RemoteModifier.padding(start = 4.rdp, bottom = (if (compact) 2 else 4).rdp)
          ) {
            RemoteText(
              text = entityText(data.entityId, "unit", data.unit),
              color = theme.secondaryText,
              fontSize = if (compact) HaType.Label else HaType.BodyLarge,
              style = haTextStyle(),
              maxLines = 1,
            )
          }
        }
      }
      if (data.periodLabel != null && !compact) {
        RemoteText(
          text = cardText("period", data.periodLabel),
          color = haInk(data.accent),
          fontSize = HaType.Caption,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
    }
  }
}
