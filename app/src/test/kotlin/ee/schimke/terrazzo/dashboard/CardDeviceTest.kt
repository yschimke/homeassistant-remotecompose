package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A card's device is its main entity's: the one a device-specific design is picked for. */
class CardDeviceTest {
  private val boiler = DeviceInfo(integration = "vaillant", model = "ecoTEC plus")
  private val snapshot = HaSnapshot(devices = mapOf("sensor.boiler_flow" to boiler))

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
      )
    )
  }
}
