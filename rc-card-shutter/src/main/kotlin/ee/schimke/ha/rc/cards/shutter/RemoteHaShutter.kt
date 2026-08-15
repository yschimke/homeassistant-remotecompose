@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.components.toRemoteAction

/**
 * Minimal port of `enhanced-shutter-card` (HACS) — native Compose, no image compositing, no tilt,
 * no 3D, no presets. One shutter row per entity: name, a window-plus-shutter visualisation, up /
 * stop / down buttons, and a position label.
 *
 * Position is captured statically at emission time (from the cover's `current_position` attribute).
 * A host that wants live motion re-encodes when the attribute changes; animating without re-encode
 * would need a numeric named binding, which alpha08 doesn't expose.
 */
data class HaShutterCardData(val title: RemoteString?, val entries: List<HaShutterEntryData>)

/** One shutter inside the card. */
data class HaShutterEntryData(
  val name: RemoteString,
  /** Fraction of the window covered, 0f (fully open) … 1f (fully closed). */
  val closedFraction: Float,
  val stateLabel: RemoteString,
  val showNameOnTop: Boolean,
  val openAction: HaAction,
  val closeAction: HaAction,
  val stopAction: HaAction,
)

@Composable
@RemoteComposable
fun RemoteHaShutter(data: HaShutterCardData, modifier: RemoteModifier = RemoteModifier) {
  val theme = currentRemoteHaTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(10.rdp)) {
      if (data.title != null) {
        RemoteText(
          text = data.title,
          color = theme.primaryText,
          fontSize = 15.rsp,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      data.entries.forEach { entry -> ShutterEntry(entry) }
    }
  }
}

@Composable
@RemoteComposable
private fun ShutterEntry(entry: HaShutterEntryData) {
  val theme = currentRemoteHaTheme()
  RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
    if (entry.showNameOnTop) {
      RemoteText(
        text = entry.name,
        color = theme.primaryText,
        fontSize = 13.rsp,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteRow(
      verticalAlignment = RemoteAlignment.CenterVertically,
      horizontalArrangement = RemoteArrangement.spacedBy(12.rdp),
    ) {
      ShutterVisualisation(entry.closedFraction)
      ShutterButtons(entry.openAction, entry.stopAction, entry.closeAction)
    }
    RemoteText(
      text = entry.stateLabel,
      color = theme.secondaryText,
      fontSize = 11.rsp,
      style = RemoteTextStyle.Default,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Window frame with a shutter panel filling the top portion by [closedFraction]. `0f` = fully open
 * (no panel drawn), `1f` = fully closed (panel fills the frame).
 */
@Composable
@RemoteComposable
private fun ShutterVisualisation(closedFraction: Float) {
  val theme = currentRemoteHaTheme()

  val frameWidthDp = 96
  val frameHeightDp = 80
  val clamped = closedFraction.coerceIn(0f, 1f)
  val panelHeightDp = (frameHeightDp * clamped).toInt()

  RemoteBox(
    modifier =
      RemoteModifier.width(frameWidthDp.rdp)
        .height(frameHeightDp.rdp)
        .clip(RemoteRoundedCornerShape(6.rdp))
        .background(theme.sectionBackground)
        .border(2.rdp, theme.divider, RemoteRoundedCornerShape(6.rdp))
  ) {
    if (panelHeightDp > 0) {
      RemoteBox(
        modifier =
          RemoteModifier.width(frameWidthDp.rdp)
            .height(panelHeightDp.rdp)
            .background(theme.placeholderAccent)
      )
    }
  }
}

@Composable
@RemoteComposable
private fun ShutterButtons(open: HaAction, stop: HaAction, close: HaAction) {
  RemoteColumn(
    verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    ShutterButton(Icons.Outlined.ArrowUpward, "Open", open)
    ShutterButton(Icons.Outlined.Stop, "Stop", stop)
    ShutterButton(Icons.Outlined.ArrowDownward, "Close", close)
  }
}

@Composable
@RemoteComposable
private fun ShutterButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  description: String,
  action: HaAction,
) {
  val theme = currentRemoteHaTheme()
  val clickable = action.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteBox(
    modifier =
      RemoteModifier.size(32.rdp).clip(RemoteCircleShape).background(theme.divider).then(clickable),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon,
      contentDescription = description.rs,
      modifier = RemoteModifier.size(18.rdp),
      tint = theme.primaryText,
    )
  }
}
