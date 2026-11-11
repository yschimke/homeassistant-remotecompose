@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable

/**
 * `area` card — composite area tile with name, summary stat row, and action chips (toggleable
 * lights/covers/fans). HA's web card also shows an optional area camera/picture; we don't render
 * that yet (alpha08 has no media-image channel into the document).
 */
@Composable
@RemoteComposable
fun RemoteHaArea(
  data: HaAreaCardData,
  modifier: RemoteModifier = RemoteModifier,
  widthDp: Int? = null,
) {
  val theme = haTheme()
  // A fixed-width cell (a launcher widget) shows the readings and chips its width holds, not a row
  // that runs off the edge; a narrow one pads less so one chip still fits.
  val padding = if (widthDp != null && widthDp < AREA_NARROW_DP) 8 else 14
  val room = widthDp?.let { it - 2 * padding }
  val stats = if (room == null) data.stats else data.stats.take((room + 12) / AREA_STAT_DP)
  val actions = if (room == null) data.actions else data.actions.take((room + 8) / AREA_CHIP_DP)
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = padding.rdp, vertical = 12.rdp),
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteText(
        text = cardText("name", data.name),
        color = theme.primaryText,
        fontSize = HaType.Title,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      if (stats.isNotEmpty()) {
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          horizontalArrangement = RemoteArrangement.spacedBy(12.rdp),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          stats.forEach { Stat(it, theme) }
        }
      }
      if (actions.isNotEmpty()) {
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth().padding(top = 4.rdp),
          horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
        ) {
          actions.forEach { ActionChip(it, theme) }
        }
      }
    }
  }
}

/** Below this width the area pads 8 dp a side, not 14. */
private const val AREA_NARROW_DP = 100

/** A reading's share of a row: its icon, a short value like "21.5 °C" and the gap after it. */
private const val AREA_STAT_DP = 84

/** An action chip's share of a row: 40 dp and the 8 dp gap after it. */
private const val AREA_CHIP_DP = 48

@Composable
private fun Stat(stat: HaAreaStat, theme: RemoteHaTheme) {
  RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
    RemoteBasicIcon(
      imageVector = stat.icon.toRemoteImageVector(),
      contentDescription = stat.label,
      modifier = RemoteModifier.size(16.rdp),
      tint = theme.secondaryText,
    )
    RemoteBox(modifier = RemoteModifier.padding(start = 6.rdp)) {
      RemoteText(
        text = stat.label,
        color = theme.secondaryText,
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun ActionChip(action: HaAreaAction, theme: RemoteHaTheme) {
  val click =
    action.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val accent = haInk(action.accent)
  val activeBg: RemoteColor = accent.copy(alpha = accent.alpha * 0.18f.rf)
  val inactiveBg: RemoteColor = theme.divider.copy(alpha = theme.divider.alpha * 0.4f.rf)
  val activeTint: RemoteColor = accent
  val inactiveTint: RemoteColor = theme.secondaryText

  // Live `<entityId>.is_on` binding so the host can flip the chip's
  // active styling without a re-encode. Falls back to the seed value
  // when entityId is null (preview).
  val isActive = LiveValues.isOn(action.entityId, action.initiallyActive)
  val bg: RemoteColor =
    isActive?.select(activeBg, inactiveBg) ?: if (action.initiallyActive) activeBg else inactiveBg
  val tint: RemoteColor =
    isActive?.select(activeTint, inactiveTint)
      ?: if (action.initiallyActive) activeTint else inactiveTint

  RemoteBox(
    modifier =
      RemoteModifier.then(click)
        .size(40.rdp)
        .clip(haControlShape(20, round = true))
        .background(bg)
        .then(haKeyChrome(tint)),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteBasicIcon(
      imageVector = action.icon.toRemoteImageVector(),
      contentDescription = "action".rs,
      modifier = RemoteModifier.size(20.rdp),
      tint = tint,
    )
  }
}
