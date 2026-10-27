package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class EntityControlsTest {

  private fun entity(id: String, state: String, vararg attrs: Pair<String, JsonElement>) =
    EntityState(id, state, JsonObject(mapOf(*attrs)))

  private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

  private fun HaAction.data(key: String) = (this as HaAction.CallService).serviceData[key]

  @Test
  fun aDimmableLightGetsPowerBrightnessAndColourTemperature() {
    val light =
      entity(
        "light.desk",
        "on",
        "supported_color_modes" to strings("color_temp"),
        "brightness" to JsonPrimitive(128),
        "min_color_temp_kelvin" to JsonPrimitive(2000),
        "max_color_temp_kelvin" to JsonPrimitive(6500),
        "color_temp_kelvin" to JsonPrimitive(3000),
      )

    val controls = entityControls("light.desk", light)

    val power = assertIs<EntityControl.Power>(controls[0])
    assertTrue(power.on)
    assertEquals(HaAction.CallService("light", "turn_off", "light.desk"), power.turnOff)
    val brightness = assertIs<EntityControl.Level>(controls[1])
    assertEquals(50.2f, brightness.value!!, 0.1f)
    assertEquals(JsonPrimitive(75), brightness.set(75f).data("brightness_pct"))
    val temperature = assertIs<EntityControl.Level>(controls[2])
    assertEquals(2000f..6500f, temperature.range)
    assertEquals(JsonPrimitive(4000), temperature.set(4000f).data("color_temp_kelvin"))
  }

  @Test
  fun anOnOffLightIsOnlyASwitch() {
    val light = entity("light.porch", "off", "supported_color_modes" to strings("onoff"))
    assertEquals(1, entityControls("light.porch", light).size)
  }

  @Test
  fun aCoverShowsWhatItSupports() {
    // Open, close and set position; no stop.
    val cover =
      entity(
        "cover.blind",
        "open",
        "supported_features" to JsonPrimitive(7),
        "current_position" to JsonPrimitive(40),
      )

    val controls = entityControls("cover.blind", cover)

    val buttons = assertIs<EntityControl.Buttons>(controls[0]).buttons
    assertEquals(listOf("Open", "Close"), buttons.map { it.label })
    assertTrue(buttons[0].active)
    val position = assertIs<EntityControl.Level>(controls[1])
    assertEquals(40f, position.value)
    assertEquals(
      HaAction.CallService(
        "cover",
        "set_cover_position",
        "cover.blind",
        JsonObject(mapOf("position" to JsonPrimitive(65))),
      ),
      position.set(65f),
    )
  }

  @Test
  fun aThermostatStepsItsTargetAndPicksAMode() {
    val climate =
      entity(
        "climate.hall",
        "heat",
        "supported_features" to JsonPrimitive(1),
        "temperature" to JsonPrimitive(20.5),
        "target_temp_step" to JsonPrimitive(0.5),
        "min_temp" to JsonPrimitive(7),
        "max_temp" to JsonPrimitive(30),
        "hvac_modes" to strings("off", "heat", "auto"),
      )

    val controls = entityControls("climate.hall", climate)

    val target = assertIs<EntityControl.Stepper>(controls[0])
    assertEquals(20.5f, target.value)
    assertEquals(JsonPrimitive(21.0), target.set(21f).data("temperature"))
    val mode = assertIs<EntityControl.Choice>(controls[1])
    assertEquals("heat", mode.selected)
    assertEquals(JsonPrimitive("auto"), mode.choose("auto").data("hvac_mode"))
  }

  @Test
  fun aFahrenheitInstanceStepsWholeDegrees() {
    // No unit or step on the entity: the instance's unit decides.
    val climate =
      entity(
        "climate.den",
        "heat",
        "supported_features" to JsonPrimitive(1),
        "temperature" to JsonPrimitive(68),
        "min_temp" to JsonPrimitive(45),
        "max_temp" to JsonPrimitive(95),
      )
    val target = assertIs<EntityControl.Stepper>(entityControls("climate.den", climate, "°F")[0])
    assertEquals(1f, target.step)
    assertEquals("°F", target.unit)
  }

  @Test
  fun aMediaPlayerOnlyTurnsTheWaysItSupports() {
    // Turn on only.
    val player = entity("media_player.tv", "on", "supported_features" to JsonPrimitive(128))
    val power = assertIs<EntityControl.Power>(entityControls("media_player.tv", player)[0])
    assertEquals(null, power.turnOff)
    assertEquals(HaAction.CallService("media_player", "turn_on", "media_player.tv"), power.turnOn)
  }

  @Test
  fun aLargeNumberIsSentWhole() {
    val number =
      entity(
        "number.budget",
        "1000",
        "min" to JsonPrimitive(0),
        "max" to JsonPrimitive(10_000_000),
      )
    val level = assertIs<EntityControl.Level>(entityControls("number.budget", number)[0])
    assertEquals(JsonPrimitive(5_000_000.0), level.set(5_000_000f).data("value"))
  }

  @Test
  fun aPlayingMediaPlayerOffersPauseAndVolume() {
    // Pause, volume set, previous, next, play.
    val features = 1 or 4 or 16 or 32 or 16384
    val player =
      entity(
        "media_player.kitchen",
        "playing",
        "supported_features" to JsonPrimitive(features),
        "volume_level" to JsonPrimitive(0.3),
      )

    val controls = entityControls("media_player.kitchen", player)

    val transport = assertIs<EntityControl.Buttons>(controls[0]).buttons
    assertEquals(listOf("Previous", "Pause", "Next"), transport.map { it.label })
    val volume = assertIs<EntityControl.Level>(controls[1])
    assertEquals(30f, volume.value!!, 0.01f)
    assertEquals(JsonPrimitive(0.55), volume.set(55f).data("volume_level"))
  }

  @Test
  fun aNumberSnapsToItsStep() {
    val number =
      entity(
        "input_number.level",
        "2.5",
        "min" to JsonPrimitive(0),
        "max" to JsonPrimitive(5),
        "step" to JsonPrimitive(0.5),
      )
    val level = assertIs<EntityControl.Level>(entityControls("input_number.level", number)[0])
    assertEquals(2.5f, level.value)
    assertEquals(3.5f, snapToStep(3.4f, level.range, level.step))
    assertEquals(5f, snapToStep(9f, level.range, level.step))
    assertEquals(JsonPrimitive(3.5), level.set(3.5f).data("value"))
  }

  @Test
  fun readOnlyAndUnavailableEntitiesHaveNoControls() {
    assertTrue(entityControls("sensor.temp", entity("sensor.temp", "21")).isEmpty())
    assertTrue(entityControls("switch.fan", entity("switch.fan", "unavailable")).isEmpty())
    assertTrue(entityControls("switch.fan", null).isEmpty())
  }

  @Test
  fun levelsFormatToTheirStep() {
    assertEquals("40%", formatLevel(40f, 1f, "%"))
    assertEquals("20.5°C", formatLevel(20.5f, 0.5f, "°C"))
    assertEquals("3000 K", formatLevel(3000f, 100f, "K"))
    assertEquals("1.25", formatLevel(1.25f, 0.05f, null))
  }

  @Test
  fun aColourLightOffersSwatchesAndRingsItsOwnColour() {
    val light =
      entity(
        "light.strip",
        "on",
        "supported_color_modes" to strings("hs"),
        "color_mode" to JsonPrimitive("hs"),
        "hs_color" to JsonArray(listOf(JsonPrimitive(215.0), JsonPrimitive(80.0))),
      )
    val colour =
      entityControls("light.strip", light).filterIsInstance<EntityControl.Colour>().single()
    assertEquals(220f, colour.hue)
    assertEquals(
      JsonArray(listOf(JsonPrimitive(0f), JsonPrimitive(100))),
      colour.set(0f).data("hs_color"),
    )
    // White, or a colour temperature, rings no swatch.
    val warm =
      entity(
        "light.strip",
        "on",
        "supported_color_modes" to strings("hs", "color_temp"),
        "color_mode" to JsonPrimitive("color_temp"),
        "hs_color" to JsonArray(listOf(JsonPrimitive(30.0), JsonPrimitive(60.0))),
      )
    assertEquals(
      null,
      entityControls("light.strip", warm).filterIsInstance<EntityControl.Colour>().single().hue,
    )
    // Hues wrap around the wheel: 350 is nearest red.
    assertEquals(0f, nearestPresetHue(350f))
  }

  @Test
  fun unlockingAsksFirst() {
    val lock = entity("lock.front", "locked", "friendly_name" to JsonPrimitive("Front door"))
    val buttons = assertIs<EntityControl.Buttons>(entityControls("lock.front", lock)[0]).buttons
    assertEquals(null, buttons[0].confirm)
    assertEquals("Unlock Front door?", buttons[1].confirm)
  }
}
