@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.border
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import androidx.compose.runtime.Composable

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
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
    ) {
      RemoteText(
        text = data.title.rs,
        color = theme.primaryText,
        fontSize = HaType.BodyLarge,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
      )
      RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.Top,
      ) {
        // An equal share each, so a long material name ellipsizes within its slot instead of
        // pushing the last slot out of the row (#1038). The gap is each slot's own padding: the
        // player shares a row by weight before taking out `spacedBy` gaps, so those overflow it.
        data.slots.forEach { slot ->
          SpoolSlot(
            slot,
            theme,
            big = false,
            modifier = RemoteModifier.weight(1f.rf).padding(horizontal = HaSpace.XS.rdp),
          )
        }
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
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
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
        if (haLayouts().notes == HaNoteStyle.Tape) ConsoleTapeLabel(data.slot.material)
        else
          RemoteText(
            text = data.slot.material,
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = RemoteFontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
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
private fun SpoolSlot(
  slot: HaBambuSpoolSlot,
  theme: RemoteHaTheme,
  big: Boolean,
  modifier: RemoteModifier = RemoteModifier,
) {
  RemoteColumn(modifier = modifier, horizontalAlignment = RemoteAlignment.CenterHorizontally) {
    SpoolSwatch(slot, big = big, theme = theme)
    RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
      if (haLayouts().notes == HaNoteStyle.Tape)
        ConsoleTapeLabel(slot.material, modifier = RemoteModifier.fillMaxWidth(), compact = true)
      else
        RemoteText(
          text = slot.material,
          color = theme.primaryText,
          fontSize = HaType.Caption,
          fontWeight = RemoteFontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
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
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
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
        .clip(haControlShape(if (big) 16 else 10))
        .background(theme.cardBackground)
        .border(
          if (slot.active) 2.rdp else 1.rdp,
          outline,
          haControlShape(if (big) 16 else 10),
        ),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(innerSize).clip(haControlShape(if (big) 12 else 8)).background(swatch),
    )
  }
}
