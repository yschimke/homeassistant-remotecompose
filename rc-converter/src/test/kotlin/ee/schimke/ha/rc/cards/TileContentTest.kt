package ee.schimke.ha.rc.cards

import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.HaNamedColor
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaTileFeature
import ee.schimke.ha.rc.components.HaTileFeaturePosition
import ee.schimke.ha.rc.icons.HaIconMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The tile's Home Assistant extras ([TileContent]): `state_content`, the climate `hvac_action`
 * badge, `color:` and the `features:` rows with the service calls they fire. Values are the Climate
 * demo dashboard's (`dashboards/climate`).
 */
class TileContentTest {

  private val tile = TileCardConverter()

  private fun climate(
    id: String = "climate.kids_room",
    state: String = "heat",
    action: String? = "heating",
    current: Double? = 21.8,
    target: Double? = 22.0,
    vararg extra: Pair<String, JsonElement>,
  ) =
    EntityState(
      id,
      state,
      JsonObject(
        buildMap {
          put("hvac_modes", JsonArray(listOf(JsonPrimitive("heat"), JsonPrimitive("off"))))
          put("min_temp", JsonPrimitive(10))
          put("max_temp", JsonPrimitive(32))
          current?.let { put("current_temperature", JsonPrimitive(it)) }
          target?.let { put("temperature", JsonPrimitive(it)) }
          action?.let { put("hvac_action", JsonPrimitive(it)) }
          put("preset_modes", JsonArray(listOf(JsonPrimitive("none"), JsonPrimitive("eco"))))
          put("preset_mode", JsonPrimitive("none"))
          put("supported_features", JsonPrimitive(401))
          putAll(extra)
        }
      ),
    )

  private fun card(
    entity: String = "climate.kids_room",
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
  ) =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", entity)
          block()
        },
    )

  /** The demo's climate tile: `target-temperature` and a dropdown `climate-hvac-modes`. */
  private fun demoCard(entity: String = "climate.kids_room") =
    card(entity) {
      put("name", "Kitchen")
      putJsonArray("features") {
        addJsonObject { put("type", "target-temperature") }
        addJsonObject {
          put("type", "climate-hvac-modes")
          put("style", "dropdown")
        }
      }
    }

  // --- state_content ----------------------------------------------------------------------------

  @Test
  fun aClimateTileDefaultsToModeAndCurrentTemperature() {
    val content = assertNotNull(TileContent.stateContent(card(), "climate.kids_room"))
    assertEquals(listOf("state", "current_temperature"), content)
    assertEquals("Heat · 21.8 °C", TileContent.secondaryText(content, climate()))
  }

  @Test
  fun stateContentTakesAStringOrAList() {
    val list = card {
      putJsonArray("state_content") {
        add("hvac_action")
        add("current_temperature")
      }
    }
    val content = assertNotNull(TileContent.stateContent(list, "climate.kids_room"))
    assertEquals("Heating · 21.8 °C", TileContent.secondaryText(content, climate()))

    val single = card { put("state_content", "temperature") }
    assertEquals(
      "22 °C",
      TileContent.secondaryText(
        assertNotNull(TileContent.stateContent(single, "climate.kids_room")),
        climate(),
      ),
    )
  }

  @Test
  fun aPlainStateStaysTheBinding() {
    assertNull(TileContent.stateContent(card("light.kitchen"), "light.kitchen"))
    assertNull(TileContent.stateContent(card { put("state_content", "state") }, "climate.x"))
  }

  @Test
  fun missingPartsAreSkippedAndNoneFallsBackToTheState() {
    val off = climate(state = "off", action = "off", current = null, target = null)
    assertEquals("Off", TileContent.secondaryText(listOf("state", "current_temperature"), off))
    assertEquals("Off", TileContent.secondaryText(listOf("current_temperature"), off))
    val cover =
      EntityState(
        "cover.blind",
        "closed",
        JsonObject(mapOf("current_position" to JsonPrimitive(0))),
      )
    // HA hides a cover position of 0.
    assertEquals("Closed", TileContent.secondaryText(listOf("state", "current_position"), cover))
  }

  @Test
  fun unavailableReadsUnavailable() {
    val gone = EntityState("climate.kids_room", "unavailable")
    assertEquals(
      "Unavailable",
      TileContent.secondaryText(listOf("state", "current_temperature"), gone),
    )
    assertNull(TileContent.badge(gone))
  }

  // --- hvac_action badge ------------------------------------------------------------------------

  @Test
  fun theBadgeFollowsHvacAction() {
    val heating = assertNotNull(TileContent.badge(climate(action = "heating")))
    // The frontend's CLIMATE_HVAC_ACTION_ICONS, as MDI glyphs.
    assertEquals(HaIconMap.mdi("fire"), heating.icon)
    assertEquals(HaStateColor.climateModeColor("heat"), heating.color)

    val idle = assertNotNull(TileContent.badge(climate(action = "idle")))
    assertEquals(HaIconMap.mdi("clock-outline"), idle.icon)
    assertNull(idle.color, "idle maps to the off mode: the neutral colour")

    val cooling = assertNotNull(TileContent.badge(climate(state = "cool", action = "cooling")))
    assertEquals(HaStateColor.climateModeColor("cool"), cooling.color)
    assertEquals(HaIconMap.mdi("snowflake"), cooling.icon)
    val defrosting = assertNotNull(TileContent.badge(climate(action = "defrosting")))
    assertEquals(HaIconMap.mdi("snowflake-melt"), defrosting.icon)

    assertNull(TileContent.badge(climate(action = "off")))
    assertNull(TileContent.badge(climate(action = null)))
    assertNull(TileContent.badge(EntityState("light.kitchen", "on")))
  }

  // --- color -----------------------------------------------------------------------------------

  @Test
  fun namedColoursAreHasPalette() {
    assertEquals(Color(0xFF2196F3), HaNamedColor.resolve("blue"))
    assertEquals(Color(0xFF4CAF50), HaNamedColor.resolve("green"))
    assertEquals(Color(0xFFF44336), HaNamedColor.resolve("red"))
    assertEquals(Color(0xFFFF6F22), HaNamedColor.resolve("deep-orange"))
    assertEquals(Color(0xFF607D8B), HaNamedColor.resolve("blue-grey"))
    assertEquals(Color(0xFF009AC7), HaNamedColor.resolve("primary"))
    assertEquals(Color(0xFFBDBDBD), HaNamedColor.resolve("disabled"))
    assertEquals(Color(0xFFFF0000), HaNamedColor.resolve("#f00"))
    assertEquals(Color(0xFF12AB34), HaNamedColor.resolve("#12ab34"))
    assertNull(HaNamedColor.resolve("state"))
    assertNull(HaNamedColor.resolve(null))
    assertNull(HaNamedColor.resolve("not-a-colour"))
    // Every name HA's THEME_COLORS lists.
    for (name in
      listOf(
        "primary",
        "accent",
        "red",
        "pink",
        "purple",
        "deep-purple",
        "indigo",
        "blue",
        "light-blue",
        "cyan",
        "teal",
        "green",
        "light-green",
        "lime",
        "yellow",
        "amber",
        "orange",
        "deep-orange",
        "brown",
        "light-grey",
        "grey",
        "dark-grey",
        "blue-grey",
        "black",
        "white",
      )) {
      assertNotNull(HaNamedColor.resolve(name), name)
    }
  }

  @Test
  fun aColouredSensorTileKeepsTheBindingPath() {
    val snapshot = HaSnapshot(states = mapOf("sensor.req_ok" to EntityState("sensor.req_ok", "12")))
    assertNull(tile.dataSignature(card("sensor.req_ok") { put("color", "green") }, snapshot))
    assertNull(
      tile.designProperties(card("sensor.req_ok") { put("color", "green") }, snapshot),
      "a configured colour renders through the converter",
    )
  }

  // --- features --------------------------------------------------------------------------------

  @Test
  fun theDemoFeaturesParse() {
    val features = TileContent.features(demoCard(), climate())
    assertEquals(2, features.size)
    val target = assertIs<HaTileFeature.NumberButtons>(features[0])
    assertEquals("22.0 °C", target.valueLabel)
    val modes = assertIs<HaTileFeature.ModeSelect>(features[1])
    // HA lists the modes in reverse HVAC_MODES order: off first.
    assertEquals(listOf("Off", "Heat"), modes.options.map { it.label })
    // The frontend's CLIMATE_HVAC_MODE_ICONS, as MDI glyphs.
    assertEquals(
      listOf(HaIconMap.mdi("power"), HaIconMap.mdi("fire")),
      modes.options.map { it.icon },
    )
    assertEquals(listOf(false, true), modes.options.map { it.selected })
    // A dropdown can't open in a document: it draws as labelled buttons.
    assertEquals(false, modes.showIcons)
  }

  @Test
  fun targetTemperatureStepsBySetTemperature() {
    val target = assertNotNull(TileContent.targetTemperature(climate(target = 22.0)))
    assertEquals(setTemperature(21.5), target.decrement)
    assertEquals(setTemperature(22.5), target.increment)

    val stepped =
      assertNotNull(
        TileContent.targetTemperature(
          climate(target = 21.0, extra = arrayOf("target_temp_step" to JsonPrimitive(1)))
        )
      )
    assertEquals("21 °C", stepped.valueLabel)
    assertEquals(setTemperature(20.0), stepped.decrement)
    assertEquals(setTemperature(22.0), stepped.increment)
  }

  @Test
  fun targetTemperatureStopsAtTheRange() {
    val top = assertNotNull(TileContent.targetTemperature(climate(target = 32.0)))
    assertEquals(HaAction.None, top.increment)
    assertEquals(setTemperature(31.5), top.decrement)
    val nearBottom = assertNotNull(TileContent.targetTemperature(climate(target = 10.2)))
    assertEquals(setTemperature(10.0), nearBottom.decrement, "a step past min clamps to min")
  }

  @Test
  fun fahrenheitStepsByOneDegree() {
    val f =
      assertNotNull(
        TileContent.targetTemperature(
          climate(
            target = 70.0,
            extra =
              arrayOf(
                "temperature_unit" to JsonPrimitive("°F"),
                "min_temp" to JsonPrimitive(45),
                "max_temp" to JsonPrimitive(95),
              ),
          )
        )
      )
    assertEquals("70 °F", f.valueLabel)
    assertEquals(setTemperature(71.0), f.increment)
  }

  @Test
  fun aFahrenheitInstanceWritesClimateTemperaturesInFahrenheit() {
    // A climate entity carries no unit: HA's unit system (get_config) decides it.
    val content = listOf("state", "current_temperature")
    assertEquals(
      "Heat · 71.5 °F",
      TileContent.secondaryText(content, climate(current = 71.5), unitSystem = "°F"),
    )
    val f =
      assertNotNull(
        TileContent.targetTemperature(
          climate(
            target = 70.0,
            extra = arrayOf("min_temp" to JsonPrimitive(45), "max_temp" to JsonPrimitive(95)),
          ),
          unitSystem = "°F",
        )
      )
    assertEquals("70 °F", f.valueLabel)
    assertEquals(setTemperature(71.0), f.increment)
  }

  @Test
  fun aThermostatWithNoSetPointDrawsDisabled() {
    val off = assertNotNull(TileContent.targetTemperature(climate(state = "off", target = null)))
    assertEquals("—", off.valueLabel)
    assertEquals(HaAction.None, off.increment)
    assertEquals(HaAction.None, off.decrement)
  }

  @Test
  fun hvacModesCallSetHvacMode() {
    val modes = assertNotNull(TileContent.hvacModes(climate(), JsonObject(emptyMap())))
    assertEquals(true, modes.showIcons, "HA's hvac modes default to the icons style")
    assertEquals(
      HaAction.CallService(
        domain = "climate",
        service = "set_hvac_mode",
        entityId = "climate.kids_room",
        serviceData = JsonObject(mapOf("hvac_mode" to JsonPrimitive("off"))),
      ),
      modes.options.first().action,
    )
    // A configured list narrows and orders the modes; unsupported ones drop out.
    val narrowed =
      assertNotNull(
        TileContent.hvacModes(
          climate(),
          buildJsonObject {
            putJsonArray("hvac_modes") {
              add("heat")
              add("cool")
              add("off")
            }
          },
        )
      )
    assertEquals(listOf("Heat", "Off"), narrowed.options.map { it.label })
  }

  @Test
  fun anUnavailableEntitysControlsFireNothing() {
    val gone = climate(state = "unavailable", action = null)
    val features = TileContent.features(demoCard(), gone)
    val target = assertIs<HaTileFeature.NumberButtons>(features[0])
    assertEquals(HaAction.None, target.increment)
    val modes = assertIs<HaTileFeature.ModeSelect>(features[1])
    assertEquals(setOf<HaAction>(HaAction.None), modes.options.map { it.action }.toSet())
  }

  @Test
  fun presetModesCallSetPresetMode() {
    val card = card {
      putJsonArray("features") { addJsonObject { put("type", "climate-preset-modes") } }
    }
    val modes = assertIs<HaTileFeature.ModeSelect>(TileContent.features(card, climate()).single())
    assertEquals(listOf("None", "Eco"), modes.options.map { it.label })
    assertEquals(
      HaAction.CallService(
        "climate",
        "set_preset_mode",
        "climate.kids_room",
        JsonObject(mapOf("preset_mode" to JsonPrimitive("eco"))),
      ),
      modes.options[1].action,
    )
  }

  @Test
  fun unsupportedOrInapplicableFeaturesAreIgnored() {
    val card = card {
      putJsonArray("features") {
        addJsonObject { put("type", "light-brightness") }
        addJsonObject { put("type", "target-temperature") }
      }
    }
    assertEquals(1, TileContent.features(card, climate()).size)
    // A climate feature on a light draws nothing.
    assertEquals(emptyList(), TileContent.features(demoCard("light.kitchen"), lightOn()))
  }

  // --- sizing and the update path --------------------------------------------------------------

  @Test
  fun featureRowsGrowTheNaturalHeight() {
    val snapshot = HaSnapshot(states = mapOf("climate.kids_room" to climate()))
    assertEquals(56, tile.naturalHeightDp(card(), snapshot))
    // HA: a 42dp control under a 12dp gap per row.
    assertEquals(56 + 2 * 54, tile.naturalHeightDp(demoCard(), snapshot))
    val inline =
      demoCard().let {
        CardConfig("tile", JsonObject(it.raw + ("features_position" to JsonPrimitive("inline"))))
      }
    assertEquals(HaTileFeaturePosition.Inline, TileContent.featuresPosition(inline))
    assertEquals(56 + 54, tile.naturalHeightDp(inline, snapshot))
  }

  @Test
  fun bakedTilesReEncodeAndPlainTilesBind() {
    val snapshot =
      HaSnapshot(states = mapOf("climate.kids_room" to climate(), "light.kitchen" to lightOn()))
    assertNotNull(tile.dataSignature(demoCard(), snapshot))
    assertNotNull(tile.dataSignature(card(), snapshot), "the climate default secondary line")
    assertNull(tile.dataSignature(card("light.kitchen"), snapshot))
    assertNull(tile.designProperties(demoCard(), snapshot))
  }

  private fun lightOn() = EntityState("light.kitchen", "on")

  private fun setTemperature(value: Double) =
    HaAction.CallService(
      domain = "climate",
      service = "set_temperature",
      entityId = "climate.kids_room",
      serviceData = JsonObject(mapOf("temperature" to JsonPrimitive(value))),
    )
}
