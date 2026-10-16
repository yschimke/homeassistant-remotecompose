package ee.schimke.ha.rc.icons

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.icons.mdi.MdiIcons
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Home Assistant's icons in Material Design Icons: overrides, domain defaults, state variants. */
class HaIconMapTest {

  private fun entity(id: String, state: String = "on", vararg attrs: Pair<String, Any>) =
    EntityState(
      id,
      state,
      JsonObject(
        attrs.associate { (k, v) ->
          k to
            when (v) {
              is Boolean -> JsonPrimitive(v)
              is Number -> JsonPrimitive(v)
              else -> JsonPrimitive(v.toString())
            }
        }
      ),
    )

  private fun name(entity: EntityState?, override: String? = null) =
    HaIconMap.resolveName(override, entity)

  // --- the demo dashboards ---

  /**
   * Not Material Design Icons at the bundled version (a newer MDI name, or a custom icon set): Home
   * Assistant draws nothing for them either, so they fall back to the entity's default.
   */
  private val notInMdi = setOf("calendar-yesterday", "spool")

  private val dashboards: File =
    File(
      System.getProperty("harc.demoDashboards")
        ?: "../terrazzo-core/src/androidMain/resources/dashboards"
    )

  private fun dashboardFiles(): List<File> =
    dashboards
      .walkTopDown()
      .filter { it.isFile && it.extension == "json" }
      .toList()
      .also { assertTrue(it.isNotEmpty(), "no demo dashboards under $dashboards") }

  @Test
  fun everyIconTheDemoDashboardsNameIsBundled() {
    val names =
      dashboardFiles()
        .flatMap { file ->
          Regex("""mdi:([a-z0-9]+(?:-[a-z0-9]+)*)""").findAll(file.readText()).map {
            it.groupValues[1]
          }
        }
        .toSortedSet()
    assertTrue(names.size > 150, "expected the demo dashboards' icons, found ${names.size}")
    val missing = names.filter { it !in notInMdi && HaIconMap.mdi("mdi:$it") == null }
    assertEquals(emptyList(), missing, "demo dashboard icons with no bundled glyph")
    for (name in names - notInMdi) {
      assertEquals("mdi:$name", HaIconMap.resolve("mdi:$name", null).name)
    }
    // Keep the exception list honest: these really aren't bundled, and they fall back.
    for (name in notInMdi) {
      assertNull(HaIconMap.mdi("mdi:$name"))
      assertEquals(HaIconTables.DEFAULT_ICON, name(null, "mdi:$name"))
    }
  }

  @Test
  fun noDemoEntityFallsBackToTheDefaultIcon() {
    val states =
      dashboardFiles()
        .filter { it.name == "entity_states.json" }
        .flatMap { file ->
          // `{ "<entity_id>": { "entity_id", "state", "attributes", … }, … }`
          Json.parseToJsonElement(file.readText()).jsonObject.values.map {
            val obj = it.jsonObject
            EntityState(
              obj.getValue("entity_id").jsonPrimitive.content,
              obj["state"]?.jsonPrimitive?.content ?: "",
              obj["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
            )
          }
        }
    assertTrue(states.size > 50, "expected the demo entities, found ${states.size}")
    val fallen =
      states.filter { name(it) == HaIconTables.DEFAULT_ICON }.map { it.entityId }.sorted()
    assertEquals(emptyList(), fallen, "demo entities drawn with the default bookmark")
  }

  // --- overrides ---

  @Test
  fun overridesAreNormalisedAndBeatTheEntity() {
    val temp = entity("sensor.t", "21", "device_class" to "temperature", "icon" to "mdi:sofa")
    assertEquals("sofa", name(temp))
    assertEquals("lamp", name(temp, " MDI:Lamp "))
    assertEquals("fan", name(temp, "hass:fan"))
    assertEquals("fan", name(null, "fan"))
  }

  @Test
  fun unknownOverridesFallThroughToTheDefault() {
    val temp = entity("sensor.t", "21", "device_class" to "temperature")
    assertEquals("thermometer", name(temp, "mdi:not-an-icon"))
    assertEquals("thermometer", name(temp, "phu:rooms-living"))
    assertEquals(
      "thermometer",
      name(entity("sensor.t", "21", "device_class" to "temperature", "icon" to "si:github")),
    )
    assertEquals(HaIconTables.DEFAULT_ICON, name(null))
    assertEquals(HaIconTables.DEFAULT_ICON, name(entity("made_up.thing")))
  }

  // --- domain and device-class defaults ---

  private val defaults: List<Triple<EntityState, String, String>> =
    listOf(
      Triple(entity("sensor.a", "12", "device_class" to "duration"), "progress-clock", "duration"),
      Triple(entity("sensor.a", "3.2", "device_class" to "monetary"), "cash", "monetary"),
      Triple(entity("sensor.a", "-60", "device_class" to "signal_strength"), "wifi", "signal"),
      Triple(entity("sensor.a", "4", "device_class" to "distance"), "arrow-left-right", "distance"),
      Triple(entity("sensor.a", "idle", "device_class" to "enum"), "eye", "enum"),
      Triple(entity("sensor.a", "2026-01-01", "device_class" to "timestamp"), "clock", "timestamp"),
      Triple(entity("sensor.a", "21", "device_class" to "temperature"), "thermometer", "temp"),
      Triple(entity("sensor.a", "40", "device_class" to "humidity"), "water-percent", "humidity"),
      Triple(entity("sensor.a", "900", "device_class" to "power"), "flash", "power"),
      Triple(entity("sensor.a", "3", "device_class" to "energy"), "lightning-bolt", "energy"),
      Triple(entity("sensor.a", "1000", "device_class" to "pressure"), "gauge", "pressure"),
      Triple(entity("sensor.a", "42"), "eye", "sensor without a class"),
      Triple(entity("button.restart_router", "unknown"), "button-pointer", "button"),
      Triple(
        entity("button.r", "unknown", "device_class" to "restart"),
        "restart",
        "restart button",
      ),
      Triple(entity("select.mode", "eco"), "format-list-bulleted", "select"),
      Triple(entity("input_select.mode", "eco"), "format-list-bulleted", "input_select"),
      Triple(entity("input_number.n", "3"), "ray-vertex", "input_number"),
      Triple(entity("camera.porch", "idle"), "video", "camera"),
      Triple(entity("scene.movie", "scening"), "palette", "scene"),
      Triple(entity("script.bedtime", "off"), "script-text", "script"),
      Triple(entity("vacuum.robo", "docked"), "robot-vacuum", "vacuum"),
      Triple(entity("climate.living", "heat"), "thermostat", "climate"),
      Triple(entity("media_player.tv", "on", "device_class" to "tv"), "television", "tv"),
      Triple(entity("weather.home", "rainy"), "weather-rainy", "weather"),
      Triple(entity("timer.tea", "active"), "timer-outline", "timer"),
      Triple(entity("zone.home", "2"), "map-marker-radius", "zone"),
    )

  @Test
  fun domainAndDeviceClassDefaultsFollowHomeAssistant() {
    for ((e, expected, label) in defaults) assertEquals(expected, name(e), label)
  }

  @Test
  fun everyTableIconIsBundled() {
    val names =
      HaIconTables.RULES.flatMap { line ->
        line.split(' ').drop(2).map { it.substringAfter('=') }
      } + HaIconTables.DOMAIN_FALLBACKS.map { it.substringAfter(' ') } + HaIconTables.DEFAULT_ICON
    val missing = names.toSet().filter { it !in MdiIcons }
    assertEquals(emptyList(), missing)
    // Every bundled glyph builds into a non-empty vector.
    for (name in MdiIcons.names) {
      val icon = assertNotNull(MdiIcons[name], name)
      assertEquals(24f, icon.viewportWidth)
      assertTrue(icon.root.size > 0, name)
    }
  }

  // --- state-following glyphs ---

  @Test
  fun stateSpecialCases() {
    assertEquals("white-balance-sunny", name(entity("sun.sun", "above_horizon")))
    assertEquals("weather-night", name(entity("sun.sun", "below_horizon")))
    assertEquals("package-up", name(entity("update.core", "on")))
    assertEquals("package", name(entity("update.core", "off")))
    assertEquals("package-down", name(entity("update.core", "on", "in_progress" to true)))
    assertEquals("account", name(entity("person.ann", "home")))
    assertEquals("account-arrow-right", name(entity("person.ann", "not_home")))
    assertEquals("account", name(entity("device_tracker.phone", "home", "source_type" to "gps")))
    assertEquals(
      "lan-connect",
      name(entity("device_tracker.laptop", "home", "source_type" to "router")),
    )
    assertEquals(
      "bluetooth",
      name(entity("device_tracker.tag", "not_home", "source_type" to "bluetooth_le")),
    )
    assertEquals("clock", name(entity("input_datetime.wake", "07:00", "has_date" to false)))
  }

  @Test
  fun alarmPanelFollowsItsState() {
    val expected =
      mapOf(
        "armed_home" to "shield-home",
        "armed_away" to "shield-lock",
        "armed_night" to "shield-moon",
        "disarmed" to "shield-off",
        "triggered" to "bell-ring",
        "pending" to "shield-outline",
        "unknown" to "shield",
      )
    for ((state, icon) in expected) {
      assertEquals(icon, name(entity("alarm_control_panel.home", state)), state)
    }
  }

  @Test
  fun weatherConditionsUseTheirWeatherGlyphs() {
    val expected =
      mapOf(
        "sunny" to "weather-sunny",
        "clear-night" to "weather-night",
        "partlycloudy" to "weather-partly-cloudy",
        "cloudy" to "weather-cloudy",
        "rainy" to "weather-rainy",
        "pouring" to "weather-pouring",
        "snowy" to "weather-snowy",
        "snowy-rainy" to "weather-snowy-rainy",
        "hail" to "weather-hail",
        "fog" to "weather-fog",
        "windy" to "weather-windy",
        "windy-variant" to "weather-windy-variant",
        "lightning" to "weather-lightning",
        "lightning-rainy" to "weather-lightning-rainy",
        "exceptional" to "alert-circle-outline",
      )
    for ((condition, icon) in expected) {
      assertEquals("mdi:$icon", HaIconMap.weatherCondition(condition).name, condition)
      assertEquals(icon, name(entity("weather.home", condition)), condition)
    }
    assertEquals("mdi:weather-partly-cloudy", HaIconMap.weatherCondition(null).name)
  }

  @Test
  fun coversAndLocksFollowTheirState() {
    val garage = entity("cover.garage", "closed", "device_class" to "garage")
    assertEquals("garage", name(garage))
    assertEquals("garage-open", name(garage.copy(state = "open")))
    assertEquals("lock", name(entity("lock.front", "locked")))
    assertEquals("lock-open-variant", name(entity("lock.front", "unlocked")))
  }

  /** On/off pairs, as core's binary_sensor / light / switch tables give them. */
  private val toggles: List<Triple<EntityState, String, String>> =
    listOf(
      Triple(
        entity("binary_sensor.m", "on", "device_class" to "motion"),
        "motion-sensor-off",
        "motion-sensor",
      ),
      Triple(entity("binary_sensor.d", "on", "device_class" to "door"), "door-closed", "door-open"),
      Triple(
        entity("binary_sensor.w", "on", "device_class" to "window"),
        "window-closed",
        "window-open",
      ),
      Triple(
        entity("binary_sensor.n", "on", "device_class" to "connectivity"),
        "close-network-outline",
        "check-network-outline",
      ),
      Triple(
        entity("binary_sensor.p", "on", "device_class" to "problem"),
        "check-circle",
        "alert-circle",
      ),
      Triple(
        entity("binary_sensor.o", "on", "device_class" to "occupancy"),
        "home-outline",
        "home",
      ),
      Triple(entity("binary_sensor.x", "on"), "radiobox-blank", "checkbox-marked-circle"),
      Triple(entity("light.kitchen", "on"), "lightbulb-off", "lightbulb"),
      Triple(entity("switch.fan", "on"), "toggle-switch-variant-off", "toggle-switch-variant"),
      Triple(
        entity("switch.plug", "on", "device_class" to "outlet"),
        "power-plug-off",
        "power-plug",
      ),
      Triple(entity("fan.ceiling", "on"), "fan-off", "fan"),
    )

  @Test
  fun onOffEntitiesCarryBothGlyphsWhateverTheirState() {
    for ((e, off, on) in toggles) {
      for (state in listOf("on", "off")) {
        val icons = HaIconMap.entityIcons(null, e.copy(state = state))
        assertEquals("mdi:$off", icons.icon.name, "${e.entityId} $state")
        assertEquals("mdi:$on", icons.activeIcon?.name, "${e.entityId} $state")
      }
      // A document that can't swap draws one glyph whatever the state at capture, and not a
      // negated one: a light reads as a light.
      val single = name(e)
      assertEquals(single, name(e.copy(state = if (e.state == "on") "off" else "on")))
      assertTrue(!single.endsWith("-off") && !single.startsWith("close-"), single)
      // Home Assistant's own glyph for each state, for content re-encoded with it.
      assertEquals(on, HaIconMap.resolveNameForState(null, e, "on"))
      assertEquals(off, HaIconMap.resolveNameForState(null, e, "off"))
      // Swapped in-document, so not a cache-key concern.
      assertNull(HaIconMap.stateSignature(e))
    }
  }

  @Test
  fun aFixedIconHasNoVariants() {
    val lamp = entity("light.lamp", "on", "icon" to "mdi:floor-lamp")
    val icons = HaIconMap.entityIcons(null, lamp)
    assertEquals("mdi:floor-lamp", icons.icon.name)
    assertNull(icons.activeIcon)
    assertNull(HaIconMap.entityIcons("mdi:lamp", entity("light.k")).activeIcon)
    assertNull(
      HaIconMap.stateSignature(entity("sun.sun", "above_horizon", "icon" to "mdi:weather-sunset"))
    )
    // Not an on/off domain: one glyph.
    assertNull(HaIconMap.entityIcons(null, entity("sun.sun", "above_horizon")).activeIcon)
  }

  @Test
  fun batteryLevelsOnlyWhenReEncoded() {
    val battery = entity("sensor.phone_battery", "45", "device_class" to "battery")
    // A bound document can't follow the level: the level-independent glyph.
    assertEquals("battery", name(battery))
    assertNull(HaIconMap.stateSignature(battery))
    fun level(state: String) = HaIconMap.resolveNameForState(null, battery, state)
    assertEquals("battery-40", level("45"))
    assertEquals("battery", level("100"))
    assertEquals("battery-alert", level("5"))
    assertEquals("battery-unknown", level("unavailable"))
  }

  @Test
  fun stateSignatureChangesWithStateFollowingGlyphs() {
    val sun = entity("sun.sun", "above_horizon")
    assertEquals("white-balance-sunny", HaIconMap.stateSignature(sun))
    assertEquals("weather-night", HaIconMap.stateSignature(sun.copy(state = "below_horizon")))
    val alarm = entity("alarm_control_panel.home", "disarmed")
    assertEquals("shield-off", HaIconMap.stateSignature(alarm))
    assertEquals("shield-home", HaIconMap.stateSignature(alarm.copy(state = "armed_home")))
    assertNull(HaIconMap.stateSignature(entity("sensor.t", "21", "device_class" to "temperature")))
    assertNull(HaIconMap.stateSignature(entity("button.b", "unknown")))
    assertNull(HaIconMap.stateSignature(null))
  }
}
