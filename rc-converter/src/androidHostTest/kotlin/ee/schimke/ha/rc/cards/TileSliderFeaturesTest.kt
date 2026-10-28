package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaTileFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The tile's slider features ([TileContent.sliderFeature]) and the release they send. */
class TileSliderFeaturesTest {

  private fun entity(id: String, state: String, vararg attributes: Pair<String, Any>) =
    EntityState(
      id,
      state,
      JsonObject(
        attributes.associate { (key, value) ->
          key to
            when (value) {
              is Number -> JsonPrimitive(value)
              is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
              else -> JsonPrimitive(value.toString())
            }
        }
      ),
    )

  private fun feature(type: String, vararg extra: Pair<String, String>) =
    JsonObject(
      mapOf("type" to JsonPrimitive(type)) +
        extra.associate { it.first to JsonPrimitive(it.second) }
    )

  private fun slider(entity: EntityState, type: String, vararg extra: Pair<String, String>) =
    TileContent.sliderFeature(entity, feature(type, *extra))

  @Test
  fun aLightsBrightnessRestsAtItsPercentAndSendsBrightnessPct() {
    val light =
      entity(
        "light.kitchen",
        "on",
        "brightness" to 158,
        "supported_color_modes" to listOf("brightness"),
      )
    val spec = assertNotNull(slider(light, "light-brightness")).spec
    assertEquals(62, spec.action.from)
    assertEquals("Brightness", spec.label)
    assertEquals("%", spec.unit)
    val call = assertNotNull(spec.action.copy(ticks = 80).toCallService())
    assertEquals("light", call.domain)
    assertEquals("turn_on", call.service)
    assertEquals(JsonPrimitive(80L), call.serviceData["brightness_pct"])
  }

  @Test
  fun anOffLightRestsAtZeroAndAnOnOffLightHasNoSlider() {
    val off = entity("light.porch", "off", "supported_color_modes" to listOf("color_temp"))
    assertEquals(0, assertNotNull(slider(off, "light-brightness")).spec.action.from)
    val onOff = entity("light.plug", "on", "supported_color_modes" to listOf("onoff"))
    assertNull(slider(onOff, "light-brightness"))
    // Not a light at all.
    assertNull(slider(entity("switch.fan", "on"), "light-brightness"))
  }

  @Test
  fun aCoverMovesOnlyWithSetPosition() {
    val blind = entity("cover.blind", "open", "current_position" to 40, "supported_features" to 15)
    val spec = assertNotNull(slider(blind, "cover-position")).spec
    assertEquals(40, spec.action.from)
    assertEquals(
      JsonPrimitive(70L),
      spec.action.copy(ticks = 70).toCallService()?.serviceData?.get("position"),
    )
    // Open / close / stop only (features 1 | 2 | 8): no set_position.
    assertNull(slider(entity("cover.door", "open", "supported_features" to 11), "cover-position"))
  }

  @Test
  fun aVolumeShowsPercentAndSendsTheLevel() {
    val speaker =
      entity("media_player.kitchen", "playing", "volume_level" to 0.25, "supported_features" to 4)
    val spec = assertNotNull(slider(speaker, "media-player-volume-slider")).spec
    assertEquals(25, spec.action.from)
    assertEquals(
      JsonPrimitive(0.6),
      spec.action.copy(ticks = 60).toCallService()?.serviceData?.get("volume_level"),
    )
  }

  @Test
  fun aNumericInputSliderMovesInTheEntitysSteps() {
    val number =
      entity(
        "input_number.target",
        "21.5",
        "min" to 15,
        "max" to 30,
        "step" to 0.5,
        "unit_of_measurement" to "°C",
      )
    // HA's default style is buttons, which this feature doesn't draw.
    assertNull(slider(number, "numeric-input"))
    val spec = assertNotNull(slider(number, "numeric-input", "style" to "slider")).spec
    assertEquals(30, spec.action.maxTicks)
    assertEquals(13, spec.action.from)
    assertEquals(1, spec.decimals)
    assertEquals("°C", spec.unit)
    val call = assertNotNull(spec.action.copy(ticks = 14).toCallService())
    assertEquals("set_value", call.service)
    assertEquals(JsonPrimitive(22L), call.serviceData["value"])
    assertEquals(
      JsonPrimitive(22.5),
      spec.action.copy(ticks = 15).toCallService()?.serviceData?.get("value"),
    )
  }

  @Test
  fun anUnavailableEntityDrawsAMutedSlider() {
    val gone =
      entity("light.kitchen", "unavailable", "supported_color_modes" to listOf("brightness"))
    assertFalse(assertNotNull(slider(gone, "light-brightness")).spec.available)
  }

  @Test
  fun aReleaseWhereItRestedOrOffTheTrackSendsNothing() {
    val action =
      HaAction.SetValue("light", "turn_on", "light.a", "brightness_pct", 0.0, 1.0, 100, 30, 30)
    assertTrue(action.isUnchanged)
    assertNull(action.toCallService())
    assertNull(action.copy(ticks = 101).toCallService())
    assertNull(action.copy(ticks = -1).toCallService())
  }

  @Test
  fun aSliderTakesARowOfItsOwn() {
    val light =
      entity(
        "light.kitchen",
        "on",
        "brightness" to 100,
        "supported_color_modes" to listOf("brightness"),
      )
    val brightness = assertNotNull(slider(light, "light-brightness"))
    val other = HaTileFeature.ModeSelect(emptyList(), showIcons = false)
    val rows =
      ee.schimke.ha.rc.components.tileFeatureRows(
        listOf(other, brightness, other, other),
        ee.schimke.ha.rc.components.HaTileFeaturePosition.Inline,
      )
    assertEquals(listOf(listOf<HaTileFeature>(brightness), listOf(other, other)), rows)
  }

  @Test
  fun aNumericSliderNeverPassesTheEntitysMaximum() {
    val number =
      entity(
        "input_number.odd",
        "4",
        "min" to 0,
        "max" to 11,
        "step" to 4,
        "unit_of_measurement" to "",
      )
    val action = assertNotNull(slider(number, "numeric-input", "style" to "slider")).spec.action
    // 0, 4, 8: a fourth step would send 12, past the maximum of 11.
    assertEquals(2, action.maxTicks)
    assertEquals(
      JsonPrimitive(8L),
      action.copy(ticks = 2).toCallService()?.serviceData?.get("value"),
    )
    val tenths =
      entity(
        "number.level",
        "0.5",
        "min" to 0,
        "max" to 1,
        "step" to 0.1,
        "unit_of_measurement" to "",
      )
    assertEquals(
      10,
      assertNotNull(slider(tenths, "numeric-input", "style" to "slider")).spec.action.maxTicks,
    )
  }
}
