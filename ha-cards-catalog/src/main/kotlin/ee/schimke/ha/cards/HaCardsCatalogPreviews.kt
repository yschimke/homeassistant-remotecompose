@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.remote.material3.RemoteButton
import androidx.wear.compose.remote.material3.RemoteCard
import androidx.wear.compose.remote.material3.RemoteSwitchButton
import androidx.wear.compose.remote.material3.RemoteText
import ee.schimke.composeai.preview.CatalogComponent

// ---------------------------------------------------------------------------------------------
// The `ha-cards` sheet: one sticker per palette entry. Discovery records the call each sticker
// makes, and ui-builder.policy.json joins those records to the builder ids a design stores
// (`ha-cards/tile`, `ha-cards/remote-text`, …).
//
// No component has a design kit behind it, so every sticker states `noReference`.
// ---------------------------------------------------------------------------------------------

private const val NO_KIT = "No published design kit covers Home Assistant cards in Remote Compose."

/**
 * HA's light-theme primary text. Remote Material 3's text takes Wear's light content colour when
 * none is given, which is unreadable on an HA light card, so the stickers (and the templates) say.
 */
private val HaPrimaryText = Color(0xFF141414).rc

/** The `homeassistant-remotecompose` sheet's tile size: a single-column dashboard card. */
private const val CARD_WIDTH_DP = 360

// ── Home Assistant ─────────────────────────────────────────────────────────────────────────────

@CatalogComponent(
  id = "HaCard/Tile",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption =
    "HA's tile card: a toggleable light, on. Bound to `light.kitchen`, so its state and accent " +
      "follow the entity live.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 72)
@Composable
fun HaCardTileSticker() = HaCardSticker {
  HaCardTile(
    name = "Kitchen",
    state = "On",
    entityId = "light.kitchen",
    icon = "light",
    toggleable = true,
    isOn = true,
  )
}

@CatalogComponent(
  id = "HaCard/EntityRow",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "One row of HA's entities card, or a whole entity card: icon, name and state.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 56)
@Composable
fun HaCardEntityRowSticker() = HaCardSticker {
  HaCardEntityRow(name = "Living room", state = "21.5 °C", icon = "climate")
}

@CatalogComponent(
  id = "HaCard/Metric",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "A reusable sensor reading with a live value, a separate unit and a custom text color.",
)
@Preview(widthDp = 168, heightDp = 88)
@Composable
fun HaCardMetricSticker() = HaCardSticker {
  HaCardMetric(
    label = "Living room",
    value = "21.5",
    unit = "°C",
    entityId = "sensor.living_room_temperature",
  )
}

@CatalogComponent(
  id = "HaCard/StatusChip",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "A small colored status indicator whose text and active state can follow an entity.",
)
@Preview(widthDp = 160, heightDp = 52)
@Composable
fun HaCardStatusChipSticker() = HaCardSticker {
  HaCardStatusChip(status = "On", entityId = "light.kitchen", isOn = true)
}

@CatalogComponent(
  id = "HaCard/Entities",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "HA's entities card in its simple form: a title over up to three rows.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 176)
@Composable
fun HaCardEntitiesSticker() = HaCardSticker {
  HaCardEntities(
    title = "Living room",
    row1Name = "Ceiling light",
    row1State = "On",
    row1Icon = "light",
    row2Name = "Temperature",
    row2State = "21.5 °C",
    row2Icon = "climate",
    row3Name = "Front door",
    row3State = "Locked",
    row3Icon = "lock",
  )
}

@CatalogComponent(
  id = "HaCard/Gauge",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "HA's gauge card: a reading on a half-circle dial between its min and max.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 200)
@Composable
fun HaCardGaugeSticker() = HaCardSticker { HaCardGauge(name = "Humidity", value = 46f, unit = "%") }

@CatalogComponent(
  id = "HaCard/Glance",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "HA's glance card: a row of icon cells, each an entity's name over its state.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 132)
@Composable
fun HaCardGlanceSticker() = HaCardSticker {
  HaCardGlance(
    title = "At a glance",
    cell1Name = "Hall",
    cell1State = "Off",
    cell1Icon = "light",
    cell2Name = "Door",
    cell2State = "Closed",
    cell2Icon = "door",
    cell3Name = "Power",
    cell3State = "320 W",
    cell3Icon = "power",
  )
}

@CatalogComponent(
  id = "HaCard/Heading",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "HA's heading card: the title a dashboard section opens with.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 56)
@Composable
fun HaCardHeadingSticker() = HaCardSticker { HaCardHeading(title = "Living room") }

@CatalogComponent(
  id = "HaCard/Button",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption =
    "HA's button card: a large icon over the entity's name. Toggleable, its accent follows the " +
      "entity and a tap toggles it.",
)
@Preview(widthDp = 160, heightDp = 160)
@Composable
fun HaCardButtonSticker() = HaCardSticker {
  HaCardButton(name = "Fan", entityId = "switch.fan", icon = "fan", toggleable = true, isOn = true)
}

@CatalogComponent(
  id = "HaCard/Toggle",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption = "The toggle switch an entities row ends in, tweening between its two accents.",
)
@Preview(widthDp = 96, heightDp = 56)
@Composable
fun HaCardToggleSticker() = HaCardSticker { HaCardToggle(isOn = true) }

@CatalogComponent(
  id = "HaCard/Icon",
  group = "Iconography",
  noReference = NO_KIT,
  caption = "An entity icon by key — light, switch, climate, sensor, fan, lock, … — tinted.",
)
@Preview(widthDp = 72, heightDp = 72)
@Composable
fun HaCardIconSticker() = HaCardSticker { HaCardIcon(icon = "light", sizeDp = 32) }

// ── Containment ────────────────────────────────────────────────────────────────────────────────

@CatalogComponent(
  id = "HaCard/Surface",
  group = "Containment",
  noReference = NO_KIT,
  caption =
    "The surface a dashboard card sits on — the theme's card background, divider and 12dp " +
      "inset — here around a heading and a tile. The Dashboard card root exports as this.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 140)
@Composable
fun HaCardSurfaceSticker() = HaCardSticker {
  HaCardSurface {
    RemoteColumn(RemoteModifier.fillMaxWidth(), horizontalAlignment = RemoteAlignment.Start) {
      HaCardHeading(title = "Kitchen", subtitle = true)
      HaCardTile(name = "Ceiling", state = "On", icon = "light")
    }
  }
}

@CatalogComponent(
  id = "Remote/Card",
  group = "Containment",
  noReference = NO_KIT,
  caption = "Remote Material 3's RemoteCard, as remote-m3 offers it.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 96)
@Composable
fun RemoteCardSticker() = HaCardSticker {
  RemoteCard(
    onClick = hostAction("ha-cards.sticker".rs),
    modifier = RemoteModifier.fillMaxWidth(),
    content = { RemoteText("Good evening".rs) },
  )
}

// ── Buttons and selection ──────────────────────────────────────────────────────────────────────

@CatalogComponent(
  id = "Remote/Button",
  group = "Buttons",
  noReference = NO_KIT,
  caption = "Remote Material 3's RemoteButton, as remote-m3 offers it: here, running a scene.",
)
@Preview(widthDp = 216, heightDp = 76)
@Composable
fun RemoteButtonSticker() = HaCardSticker {
  RemoteButton(onClick = hostAction("ha-cards.sticker".rs)) { RemoteText("Movie night".rs) }
}

@CatalogComponent(
  id = "Remote/SwitchButton",
  group = "Selection buttons",
  noReference = NO_KIT,
  caption = "Remote Material 3's RemoteSwitchButton: a labelled switch, checked.",
)
@Preview(widthDp = 216, heightDp = 76)
@Composable
fun RemoteSwitchButtonSticker() = HaCardSticker {
  RemoteSwitchButton(
    checked = true.rb,
    onCheckedChange = hostAction("ha-cards.sticker".rs),
    modifier = RemoteModifier.fillMaxWidth(),
    label = { RemoteText("Porch light".rs, color = HaPrimaryText) },
  )
}

// ── Text ───────────────────────────────────────────────────────────────────────────────────────

@CatalogComponent(
  id = "Remote/Text",
  group = "Text",
  noReference = NO_KIT,
  caption = "Remote Material 3's RemoteText, set by size: the text every template binds.",
)
@Preview(widthDp = 216, heightDp = 56)
@Composable
fun RemoteTextSticker() = HaCardSticker {
  RemoteText("Living room".rs, color = HaPrimaryText, fontSize = 18.rsp)
}
