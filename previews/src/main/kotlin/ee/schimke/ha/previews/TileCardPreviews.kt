@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tile-only previews. Canvas sizing follows the same rule as [CardPreviews] — see the "CANVAS
 * SIZING RULE" block there. DO NOT change these dimensions without updating both files together.
 *
 * Reference PNG is 492×112 px (captured at deviceScaleFactor=2 via Puppeteer). Preview renders at
 * density 2.625 (Robolectric default), so canvas = 492/2.625 × 112/2.625 ≈ 187×43 dp. The height is
 * 56 dp since the card type scale went to 15sp body text: two lines no longer fit HA web's 43.
 */
private const val TILE_WIDTH_DP = 187
private const val TILE_HEIGHT_DP = 56

@Composable
private fun TileHost(theme: HaTheme, content: @Composable () -> Unit) {
  CapturingRemoteContentPreview(profile = androidXExperimental) {
    ProvideCardRegistry(defaultRegistry()) { ProvideHaTheme(theme) { content() } }
  }
}

@Preview(
  name = "tile — sensor temperature",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_HEIGHT_DP,
)
@Composable
fun Tile_TemperatureSensor() =
  TileHost(HaTheme.Light) {
    RenderChild(
      card = card("""{"type":"tile","entity":"sensor.living_room"}"""),
      snapshot = Fixtures.livingRoomTemp,
    )
  }

@Preview(
  name = "tile — sensor temperature (dark)",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_HEIGHT_DP,
)
@Composable
fun Tile_TemperatureSensor_Dark() =
  TileHost(HaTheme.Dark) {
    RenderChild(
      card = card("""{"type":"tile","entity":"sensor.living_room"}"""),
      snapshot = Fixtures.livingRoomTemp,
    )
  }

@Preview(
  name = "tile — light on",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_HEIGHT_DP,
)
@Composable
fun Tile_LightOn() =
  TileHost(HaTheme.Light) {
    RenderChild(
      card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
      snapshot = Fixtures.kitchenLight,
    )
  }

@Preview(
  name = "tile — light on (dark)",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_HEIGHT_DP,
)
@Composable
fun Tile_LightOn_Dark() =
  TileHost(HaTheme.Dark) {
    RenderChild(
      card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
      snapshot = Fixtures.kitchenLight,
    )
  }

/** A tile with two feature rows: [TILE_HEIGHT_DP] plus a 12dp gap and a 42dp control per row. */
private const val TILE_FEATURES_HEIGHT_DP = 164

/** The Climate demo's thermostats: the kitchen heating, upstairs idle (both in `heat` mode). */
private val climateTiles: HaSnapshot =
  snapshot(
    "climate.kids_room" to climateState("climate.kids_room", "Kitchen", "heating", 21.8, 22.0),
    "climate.upstairs" to climateState("climate.upstairs", "Upstairs", "idle", 22.8, 22.7),
    "sensor.node_status" to
      EntityState(
        "sensor.node_status",
        "Online",
        JsonObject(mapOf("friendly_name" to JsonPrimitive("Status"))),
      ),
  )

private fun climateState(
  id: String,
  name: String,
  action: String,
  current: Double,
  target: Double,
): EntityState =
  EntityState(
    id,
    "heat",
    JsonObject(
      mapOf(
        "friendly_name" to JsonPrimitive(name),
        "hvac_modes" to JsonArray(listOf(JsonPrimitive("heat"), JsonPrimitive("off"))),
        "min_temp" to JsonPrimitive(10),
        "max_temp" to JsonPrimitive(32),
        "current_temperature" to JsonPrimitive(current),
        "temperature" to JsonPrimitive(target),
        "hvac_action" to JsonPrimitive(action),
        "supported_features" to JsonPrimitive(401),
      )
    ),
  )

/** The demo's climate tile config: `target-temperature` and `climate-hvac-modes` features. */
private fun climateTile(entity: String, name: String) =
  card(
    """{"type":"tile","entity":"$entity","name":"$name","icon":"mdi:thermostat",
    "features":[{"type":"target-temperature"},{"type":"climate-hvac-modes","style":"dropdown"}]}"""
  )

@Preview(
  name = "tile — climate heating, features",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_FEATURES_HEIGHT_DP,
)
@Composable
fun Tile_Climate_Heating_Features() =
  TileHost(HaTheme.Light) { RenderChild(climateTile("climate.kids_room", "Kitchen"), climateTiles) }

@Preview(
  name = "tile — climate heating, features (dark)",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_FEATURES_HEIGHT_DP,
)
@Composable
fun Tile_Climate_Heating_Features_Dark() =
  TileHost(HaTheme.Dark) { RenderChild(climateTile("climate.kids_room", "Kitchen"), climateTiles) }

@Preview(
  name = "tile — climate idle, features",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_FEATURES_HEIGHT_DP,
)
@Composable
fun Tile_Climate_Idle_Features() =
  TileHost(HaTheme.Light) { RenderChild(climateTile("climate.upstairs", "Upstairs"), climateTiles) }

@Preview(
  name = "tile — climate idle, features (dark)",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_FEATURES_HEIGHT_DP,
)
@Composable
fun Tile_Climate_Idle_Features_Dark() =
  TileHost(HaTheme.Dark) { RenderChild(climateTile("climate.upstairs", "Upstairs"), climateTiles) }

@Preview(
  name = "tile — colour: blue",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_HEIGHT_DP,
)
@Composable
fun Tile_Color_Blue() =
  TileHost(HaTheme.Light) {
    RenderChild(
      card("""{"type":"tile","entity":"sensor.node_status","icon":"mdi:chip","color":"blue"}"""),
      climateTiles,
    )
  }

@Preview(
  name = "tile — colour: blue (dark)",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = TILE_HEIGHT_DP,
)
@Composable
fun Tile_Color_Blue_Dark() =
  TileHost(HaTheme.Dark) {
    RenderChild(
      card("""{"type":"tile","entity":"sensor.node_status","icon":"mdi:chip","color":"blue"}"""),
      climateTiles,
    )
  }

/**
 * `features_position: inline`: the first feature beside the name, the rest below two to a row (a
 * spaced row of weighted controls, whose gap must stay inside the tile), a stepper on its own.
 */
@Preview(
  name = "tile — climate, inline features",
  showBackground = false,
  widthDp = TILE_WIDTH_DP,
  heightDp = 168,
)
@Composable
fun Tile_Climate_Inline_Features() =
  TileHost(HaTheme.Dark) {
    RenderChild(
      card(
        """{"type":"tile","entity":"climate.kids_room","name":"Kitchen","icon":"mdi:thermostat",
        "features_position":"inline","features":[{"type":"climate-hvac-modes","style":"dropdown"},
        {"type":"target-temperature"},{"type":"climate-hvac-modes","style":"dropdown"}]}"""
      ),
      climateTiles,
    )
  }
