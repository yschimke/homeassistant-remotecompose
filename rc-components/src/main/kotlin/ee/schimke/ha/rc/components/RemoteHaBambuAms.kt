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
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
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
 * Active slot gets a coloured outline; empty slots collapse to a
 * dotted-style placeholder.
 */
@Composable
@RemoteComposable
fun RemoteHaBambuAms(data: HaBambuAmsData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
            RemoteText(
                text = data.title,
                color = theme.primaryText.rc,
                fontSize = 14.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
            )
            RemoteRow(
                modifier = RemoteModifier.fillMaxWidth(),
                horizontalArrangement = RemoteArrangement.SpaceBetween,
                verticalAlignment = RemoteAlignment.Top,
            ) {
                data.slots.forEach { slot ->
                    SpoolSlot(slot, theme, big = false)
                }
            }
        }
    }
}

/**
 * `custom:ha-bambulab-spool-card` — single slot, larger swatch.
 */
@Composable
@RemoteComposable
fun RemoteHaBambuSpool(data: HaBambuSpoolDetail, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            SpoolSwatch(data.slot, big = true, theme = theme)
            RemoteColumn(modifier = RemoteModifier.padding(start = 14.rdp)) {
                RemoteText(
                    text = data.slot.slotLabel,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                )
                RemoteText(
                    text = data.slot.material,
                    color = theme.primaryText.rc,
                    fontSize = 16.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteText(
                    text = (data.slot.remainPercent?.let { "$it %" } ?: "—").rs,
                    color = theme.secondaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
    }
}

@Composable
private fun SpoolSlot(slot: HaBambuSpoolSlot, theme: HaTheme, big: Boolean) {
    RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
        SpoolSwatch(slot, big = big, theme = theme)
        RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
            RemoteText(
                text = slot.material,
                color = theme.primaryText.rc,
                fontSize = 11.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RemoteText(
            text = (slot.remainPercent?.let { "$it %" } ?: "—").rs,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
        )
    }
}

@Composable
private fun SpoolSwatch(slot: HaBambuSpoolSlot, big: Boolean, theme: HaTheme) {
    val outerSize = if (big) 56.rdp else 36.rdp
    val innerSize = if (big) 44.rdp else 28.rdp
    val outline = if (slot.active) slot.color else theme.divider
    RemoteBox(
        modifier = RemoteModifier
            .size(outerSize)
            .clip(RemoteRoundedCornerShape(if (big) 16.rdp else 10.rdp))
            .background(theme.cardBackground.rc)
            .border(
                if (slot.active) 2.rdp else 1.rdp,
                outline.rc,
                RemoteRoundedCornerShape(if (big) 16.rdp else 10.rdp),
            ),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteBox(
            modifier = RemoteModifier
                .size(innerSize)
                .clip(RemoteRoundedCornerShape(if (big) 12.rdp else 8.rdp))
                .background(slot.color.rc),
        )
    }
}
