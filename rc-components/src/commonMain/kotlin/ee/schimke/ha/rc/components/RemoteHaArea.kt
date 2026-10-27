@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
        .padding(horizontal = padding.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteText(
        text = cardText("name", data.name),
        color = theme.primaryText,
        fontSize = HaType.Title,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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
        .then(consoleKeyChrome(tint)),
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
