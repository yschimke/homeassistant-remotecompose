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
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * `custom:ha-bambulab-ams-card` — grid of up to four filament slots.
 *
 * ```
 *   ┌──────────────────────────────────────────────┐
 *   │  AMS                                         │
 *   │  ●  PLA       ○  PETG     ●  ASA   ●  TPU    │
 *   │     100%         87%        43%        70%   │
 *   └──────────────────────────────────────────────┘
 * ```
 *
 * Active slot gets a coloured outline; empty slots collapse to a dotted-style placeholder.
 */
@Composable
@RemoteComposable
fun RemoteHaBambuAms(data: HaBambuAmsData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteText(
        text = data.title.rs,
        color = theme.primaryText,
        fontSize = HaType.BodyLarge,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
      )
      RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.Top,
      ) {
        data.slots.forEach { slot -> SpoolSlot(slot, theme, big = false) }
      }
    }
  }
}

/** `custom:ha-bambulab-spool-card` — single slot, larger swatch. */
@Composable
@RemoteComposable
fun RemoteHaBambuSpool(data: HaBambuSpoolDetail, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
      SpoolSwatch(data.slot, big = true, theme = theme)
      RemoteColumn(modifier = RemoteModifier.padding(start = 14.rdp)) {
        RemoteText(
          text = cardText("slot_label", data.slot.slotLabel),
          color = theme.secondaryText,
          fontSize = HaType.Caption,
          style = haTextStyle(),
        )
        RemoteText(
          text = data.slot.material,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteText(
          text =
            LiveValues.attribute(
              data.slot.entityId,
              "remain_label",
              data.slot.remainPercent?.let { "$it %" } ?: "—",
            ),
          color = theme.secondaryText,
          fontSize = HaType.Body,
          style = haTextStyle(),
        )
      }
    }
  }
}

@Composable
private fun SpoolSlot(slot: HaBambuSpoolSlot, theme: RemoteHaTheme, big: Boolean) {
  RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
    SpoolSwatch(slot, big = big, theme = theme)
    RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
      RemoteText(
        text = slot.material,
        color = theme.primaryText,
        fontSize = HaType.Caption,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteText(
      text =
        LiveValues.attribute(
          slot.entityId,
          "remain_label",
          slot.remainPercent?.let { "$it %" } ?: "—",
        ),
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
    )
  }
}

@Composable
private fun SpoolSwatch(slot: HaBambuSpoolSlot, big: Boolean, theme: RemoteHaTheme) {
  val outerSize = if (big) 56.rdp else 36.rdp
  val innerSize = if (big) 44.rdp else 28.rdp
  val swatch = entityColor(slot.entityId, "color", slot.color)
  val outline = if (slot.active) swatch else theme.divider
  RemoteBox(
    modifier =
      RemoteModifier.size(outerSize)
        .clip(RemoteRoundedCornerShape(if (big) 16.rdp else 10.rdp))
        .background(theme.cardBackground)
        .border(
          if (slot.active) 2.rdp else 1.rdp,
          outline,
          RemoteRoundedCornerShape(if (big) 16.rdp else 10.rdp),
        ),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(innerSize)
          .clip(RemoteRoundedCornerShape(if (big) 12.rdp else 8.rdp))
          .background(swatch)
    )
  }
}
