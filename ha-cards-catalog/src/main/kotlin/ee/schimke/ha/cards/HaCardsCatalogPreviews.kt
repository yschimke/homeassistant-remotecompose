@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
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
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaBambuControlButton
import ee.schimke.ha.rc.components.HaBambuPrintControlData
import ee.schimke.ha.rc.components.HaClockData
import ee.schimke.ha.rc.components.RemoteHaBambuPrintControl
import ee.schimke.ha.rc.components.RemoteHaClock

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
  id = "HaCard/Thermostat",
  group = "Home Assistant",
  noReference = NO_KIT,
  caption =
    "HA's thermostat card: the room temperature on a 270° dial, the set point marked on it and " +
      "the HVAC mode above.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 250)
@Composable
fun HaCardThermostatSticker() = HaCardSticker {
  HaCardThermostat(name = "Living room", current = 21.5f, target = 22f)
}

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
  id = "HaCard/DashboardSection",
  group = "Containment",
  noReference = NO_KIT,
  caption =
    "A dashboard section: a heading over cards, each on its own surface, as HA's sections view " +
      "stacks them. The Dashboard section root exports as this.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 200)
@Composable
fun HaDashboardSectionSticker() = HaCardSticker {
  HaDashboardSection {
    RemoteColumn(RemoteModifier.fillMaxWidth(), horizontalAlignment = RemoteAlignment.Start) {
      HaCardHeading(title = "Kitchen")
      HaCardSurface { HaCardTile(name = "Ceiling", state = "On", icon = "light") }
      HaCardSurface { HaCardThermometer(name = "Kitchen", value = 21.5f, presentation = "compact") }
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

// ── Measurements ────────────────────────────────────────────────────────────────────────────────

@CatalogComponent(
  id = "HaCard/Thermometer",
  group = "Measurements",
  noReference = NO_KIT,
  caption =
    "A temperature as a thermometer, standard presentation: the glyph fills along HA's comfort " +
      "ramp and follows `<entityId>.numeric_state` live.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 96)
@Composable
fun HaCardThermometerSticker() = HaCardSticker {
  HaCardThermometer(
    name = "Living room",
    value = 21.5f,
    entityId = "sensor.living_room_temperature",
  )
}

@CatalogComponent(
  id = "HaCard/Thermometer-Adaptive-Tall",
  group = "Measurements",
  noReference = NO_KIT,
  caption =
    "The adaptive thermometer in a tall cell: expanded, the tube grows with the cell and the " +
      "range appears under a promoted reading.",
)
@Preview(widthDp = 160, heightDp = 240)
@Composable
fun HaCardThermometerTallSticker() = HaCardSticker {
  HaCardThermometer(name = "Bedroom", value = 27f, presentation = ADAPTIVE)
}

@CatalogComponent(
  id = "HaCard/Thermometer-Adaptive-Short",
  group = "Measurements",
  noReference = NO_KIT,
  caption =
    "The same adaptive thermometer in a Wear Small–shaped cell: compact, the glyph beside the " +
      "reading and name.",
)
@Preview(widthDp = 216, heightDp = 76)
@Composable
fun HaCardThermometerShortSticker() = HaCardSticker {
  HaCardThermometer(name = "Bedroom", value = 27f, presentation = ADAPTIVE)
}

@CatalogComponent(
  id = "HaCard/Thermometer-Chip",
  group = "Measurements",
  noReference = NO_KIT,
  caption = "The chip rung, for a 1×1 launcher cell: the thermometer over its reading, no name.",
)
@Preview(widthDp = 72, heightDp = 110)
@Composable
fun HaCardThermometerChipSticker() = HaCardSticker {
  HaCardThermometer(name = "Outside", value = 4f, presentation = "chip")
}

@CatalogComponent(
  id = "HaCard/Battery",
  group = "Measurements",
  noReference = NO_KIT,
  caption = "A battery level as a battery; below 30 % it turns amber, below 15 % red.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 80)
@Composable
fun HaCardBatterySticker() = HaCardSticker {
  HaCardBattery(name = "Front door lock", value = 22f, entityId = "sensor.front_door_battery")
}

@CatalogComponent(
  id = "HaCard/Measurement",
  group = "Measurements",
  noReference = NO_KIT,
  caption = "A reading drawn by its kind — humidity, on an arc — at the compact presentation.",
)
@Preview(widthDp = 216, heightDp = 76)
@Composable
fun HaCardMeasurementSticker() = HaCardSticker {
  HaCardMeasurement(
    name = "Bathroom",
    value = 64f,
    unit = "%",
    kind = "humidity",
    presentation = "compact",
  )
}

@CatalogComponent(
  id = "HaCard/ArcMeter",
  group = "Measurements",
  noReference = NO_KIT,
  caption = "A bare arc meter: a value on its scale, no text, for building a card of your own.",
)
@Preview(widthDp = 160, heightDp = 96)
@Composable
fun HaCardArcMeterSticker() = HaCardSticker {
  HaCardArcMeter(value = 62f, modifier = RemoteModifier.fillMaxWidth().height(72.rdp))
}

@CatalogComponent(
  id = "HaCard/BarMeter",
  group = "Measurements",
  noReference = NO_KIT,
  caption = "A bare bar meter: a value on its scale as a rounded bar.",
)
@Preview(widthDp = 200, heightDp = 40)
@Composable
fun HaCardBarMeterSticker() = HaCardSticker {
  HaCardBarMeter(value = 62f, modifier = RemoteModifier.fillMaxWidth().height(10.rdp))
}

@CatalogComponent(
  id = "HaCard/Template",
  group = "Containment",
  noReference = NO_KIT,
  caption =
    "A card design template: the entity names and card title inside it bind to the values the " +
      "app pushes (`light.s0.name`, `card.title`), so a design made from it can be published as " +
      "a card design.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 120)
@Composable
fun HaCardTemplateSticker() = HaCardSticker {
  HaCardTemplate {
    HaCardSurface {
      RemoteColumn(RemoteModifier.fillMaxWidth(), horizontalAlignment = RemoteAlignment.Start) {
        HaCardHeading(title = "Kitchen", subtitle = true)
        HaCardEntityRow(
          name = "Ceiling",
          state = "On",
          entityId = "light.s0",
          toggleable = true,
          isOn = true,
        )
      }
    }
  }
}

@CatalogComponent(
  id = "HaCard/UberTheme",
  group = "Containment",
  noReference = NO_KIT,
  caption =
    "An uber theme around a card: here the LCD look — segment-drawn thermometer and battery in " +
      "one ink, monospace text and a square bezel — over the same components as everywhere else.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 150)
@Composable
fun HaCardUberThemeSticker() = HaCardSticker {
  HaCardUberTheme(theme = "lcd") {
    HaCardSurface {
      RemoteColumn(RemoteModifier.fillMaxWidth(), horizontalAlignment = RemoteAlignment.Start) {
        HaCardThermometer(name = "Living room", value = 21.5f, presentation = "compact")
        HaCardBattery(name = "Front door lock", value = 64f, presentation = "compact")
      }
    }
  }
}

@CatalogComponent(
  id = "HaCard/UberThemeConsole",
  group = "Containment",
  noReference = NO_KIT,
  caption =
    "The `console` uber theme: a skeuomorphic studio desk. The thermostat is a knurled knob in a " +
      "ring of lamps, the gauge a backlit VU meter, the thermometer a glass liquid column, all on a lit metal panel.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 560)
@Composable
fun HaCardUberThemeConsoleSticker() = HaCardSticker {
  HaCardUberTheme(theme = "console", dark = true) {
    RemoteColumn(RemoteModifier.fillMaxWidth(), horizontalAlignment = RemoteAlignment.Start) {
      HaCardThermostat(name = "Living room", current = 21.5f, target = 22f)
      HaCardGauge(name = "Level", value = 62f, unit = "%")
      HaCardSurface {
        RemoteColumn(RemoteModifier.fillMaxWidth(), horizontalAlignment = RemoteAlignment.Start) {
          HaCardThermometer(name = "Outside", value = 31f, presentation = "compact")
          HaCardEntityRow(name = "Kitchen", state = "On", toggleable = true, isOn = true)
        }
      }
    }
  }
}

@CatalogComponent(
  id = "HaCard/ConsoleControls",
  group = "Containment",
  noReference = NO_KIT,
  caption =
    "Console instruments: smoked-glass LED clock, recessed transport keys with separate " +
      "pilot lamps, a mechanical rocker and an enamel-backed liquid thermometer.",
)
@Preview(widthDp = CARD_WIDTH_DP, heightDp = 420)
@Composable
fun HaCardConsoleControlsSticker() = HaCardSticker {
  HaCardUberTheme(theme = "console", dark = true) {
    RemoteColumn(RemoteModifier.fillMaxWidth(), horizontalAlignment = RemoteAlignment.Start) {
      RemoteHaClock(
        HaClockData(
          title = "STUDIO",
          staticTimeLabel = "05:17",
          secondaryLabel = null,
          isLarge = true,
          use24Hour = true,
        )
      )
      RemoteHaBambuPrintControl(
        HaBambuPrintControlData(
          printerName = "Transport",
          pause =
            HaBambuControlButton(
              "Pause",
              Icons.Filled.Pause,
              Color(0xFFFFA000),
              HaAction.None,
              true,
            ),
          resume = null,
          stop =
            HaBambuControlButton("Stop", Icons.Filled.Stop, Color(0xFFE53935), HaAction.None, true),
          statusLine = null,
        )
      )
      HaCardButton(
        name = "Fan",
        entityId = "switch.fan",
        toggleable = true,
        isOn = true,
        modifier = RemoteModifier.fillMaxWidth(),
      )
      HaCardSurface { HaCardThermometer(name = "Room temperature", value = 21.5f) }
    }
  }
}
