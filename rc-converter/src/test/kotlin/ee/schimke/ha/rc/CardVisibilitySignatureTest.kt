package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.cardVisibilitySignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A conditional showing or hiding changes what its card draws, so the host re-encodes on it: the
 * visibility signature moves exactly when a conditional anywhere in the card flips.
 */
class CardVisibilitySignatureTest {
  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = (raw["type"] as JsonPrimitive).content, raw = raw)
  }

  private fun snapshot(door: String, light: String = "off") =
    HaSnapshot(
      states =
        listOf(EntityState("binary_sensor.door", door), EntityState("light.hall", light))
          .associateBy { it.entityId }
    )

  private val grid =
    card(
      """{"type":"grid","cards":[
        {"type":"tile","entity":"light.hall"},
        {"type":"conditional","conditions":[{"entity":"binary_sensor.door","state":"on"}],
         "card":{"type":"tile","entity":"binary_sensor.door"}}]}"""
    )

  @Test
  fun aConditionFlippingMovesTheSignature() {
    assertNotEquals(
      cardVisibilitySignature(grid, snapshot(door = "off")),
      cardVisibilitySignature(grid, snapshot(door = "on")),
    )
  }

  @Test
  fun otherStateChangesLeaveItAlone() {
    // A tile's own state still arrives as a binding: no re-encode for it.
    assertEquals(
      cardVisibilitySignature(grid, snapshot(door = "off", light = "off")),
      cardVisibilitySignature(grid, snapshot(door = "off", light = "on")),
    )
  }

  @Test
  fun aCardWithoutConditionalsHasNone() {
    assertNull(
      cardVisibilitySignature(card("""{"type":"tile","entity":"light.hall"}"""), snapshot("off"))
    )
  }
}
