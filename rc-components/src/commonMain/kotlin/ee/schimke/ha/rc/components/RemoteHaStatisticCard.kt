@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow

/**
 * `statistic` card — single hero value (mean / min / max / sum) for one entity, with optional
 * period label + unit suffix. [compact] sets the name in caption type and the value a size down,
 * and leaves out the period: two lines that fit a Wear Small slot. [tight] keeps every line at its
 * size and trims the vertical padding and gaps instead (6 + 17 + 2 + 43 + 2 + 16 + 6 ≈ 84 dp), so
 * the period still fits a one-row launcher widget rather than being cut off at its bottom. A
 * launcher cell centres the card (parts draw no chrome there), so the trimmed padding does not show
 * in a taller one.
 */
@Composable
@RemoteComposable
fun RemoteHaStatisticCard(
  data: HaStatisticCardData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
  tight: Boolean = false,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(
          horizontal = 14.rdp,
          vertical =
            (when {
                compact -> 4
                tight -> 6
                else -> 12
              })
              .rdp,
        ),
  ) {
    RemoteColumn(
      verticalArrangement =
        RemoteArrangement.spacedBy(
          (when {
              compact -> 0
              tight -> 2
              else -> 4
            })
            .rdp,
        ),
    ) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = if (compact) HaType.Caption else HaType.Label,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
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
            modifier = RemoteModifier.padding(start = 4.rdp, bottom = (if (compact) 2 else 4).rdp),
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
