package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A card's device is its main entity's: the one a device-specific design is picked for. */
class CardDeviceTest {
  private val boiler = DeviceInfo(integration = "vaillant", model = "ecoTEC plus")
  private val lamp = DeviceInfo(integration = "hue", model = "LCT015")
  private val snapshot =
    HaSnapshot(devices = mapOf("sensor.boiler_flow" to boiler, "light.hall" to lamp))

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = raw["type"]!!.jsonPrimitive.content, raw = raw)
  }

  @Test
  fun theMainEntityDecides() {
    assertEquals(
      boiler,
      cardDevice(card("""{"type":"gauge","entity":"sensor.boiler_flow"}"""), snapshot),
    )
    assertEquals(
      boiler,
      cardDevice(
        card("""{"type":"entities","entities":["sensor.boiler_flow","sensor.x"]}"""),
        snapshot,
      ),
    )
  }

  @Test
  fun aLaterEntitysDeviceNeverRestylesTheCard() {
    assertNull(
      cardDevice(
        card("""{"type":"entities","entities":["sensor.x","sensor.boiler_flow"]}"""),
        snapshot,
      ),
    )
  }

  @Test
  fun anEntityAnActionNamesNeverDecides() {
    val tile =
      card(
        """
        |{"type":"tile","tap_action":{"action":"more-info","entity":"light.hall"},
        |"entity":"sensor.boiler_flow"}
        """
          .trimMargin(),
      )
    assertEquals("sensor.boiler_flow", cardMainEntity(tile))
    assertEquals(boiler, cardDevice(tile, snapshot))
  }

  @Test
  fun aStackIsItsFirstChildsAndACardWithoutSlotsItsFirstEntity() {
    val stack =
      card(
        """
        |{"type":"vertical-stack","cards":[{"type":"tile","entity":"light.hall"},
        |{"type":"gauge","entity":"sensor.boiler_flow"}]}
        """
          .trimMargin(),
      )
    assertEquals(lamp, cardDevice(stack, snapshot))
    // A map card names entities under a key slots don't read; the config's first one counts.
    val map =
      card("""{"type":"map","geo_location_sources":[],"entities":[{"entity":"light.hall"}]}""")
    assertEquals("light.hall", cardMainEntity(map))
    assertNull(cardMainEntity(card("""{"type":"markdown","content":"hi"}""")))
    assertNull(cardDevice(card("""{"type":"tile","entity":"light.unknown"}"""), snapshot))
  }

  @Test
  fun theEntitysDeviceClassComesFromItsState() {
    val garage =
      HaSnapshot(
        states =
          mapOf(
            "cover.garage" to
              EntityState(
                "cover.garage",
                "closed",
                JsonObject(mapOf("device_class" to JsonPrimitive("garage"))),
              ),
          ),
        devices = mapOf("cover.garage" to DeviceInfo(integration = "ratgdo")),
      )
    assertEquals(
      DeviceInfo(integration = "ratgdo", deviceClass = "garage"),
      cardDevice(card("""{"type":"tile","entity":"cover.garage"}"""), garage),
    )
  }
}
