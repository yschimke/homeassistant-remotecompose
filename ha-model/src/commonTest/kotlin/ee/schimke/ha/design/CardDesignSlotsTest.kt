package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CardDesignSlotsTest {

  private fun tile(entity: String, vararg extra: Pair<String, String>) =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", entity)
          for ((k, v) in extra) put(k, v)
        },
    )

  @Test
  fun slotBindingsAreKeyedBySlotAndAlwaysComplete() {
    val card = tile("light.kitchen", "name" to "Kitchen")
    val snapshot = HaSnapshot(states = mapOf("light.kitchen" to EntityState("light.kitchen", "on")))
    val bindings = CardDesignSlots.slotBindings(card, snapshot)
    assertEquals("Kitchen", bindings.strings["light.s0.name"])
    assertEquals(true, bindings.booleans["light.s0.is_on"])
    assertEquals(CardDesignSlots.STATUS_AVAILABLE, bindings.ints["light.s0.status"])
    assertEquals(-1, bindings.ints["light.s0.state_int"])
    assertTrue(bindings.floats["light.s0.numeric_state"]!!.isNaN())
    assertTrue(bindings.strings.keys.none { it.startsWith("light.kitchen") })
  }

  @Test
  fun missingAndUnavailableEntitiesStillPushEveryBinding() {
    val card = tile("sensor.temp")
    val missing = CardDesignSlots.slotBindings(card, HaSnapshot())
    assertEquals(CardDesignSlots.STATUS_MISSING, missing.ints["sensor.s0.status"])
    assertEquals("sensor.temp", missing.strings["sensor.s0.name"])

    val unavailable =
      CardDesignSlots.slotBindings(
        card,
        HaSnapshot(
          states =
            mapOf(
              "sensor.temp" to
                EntityState(
                  "sensor.temp",
                  "unavailable",
                  JsonObject(mapOf("friendly_name" to JsonPrimitive("Temp"))),
                )
            )
        ),
      )
    assertEquals(CardDesignSlots.STATUS_UNAVAILABLE, unavailable.ints["sensor.s0.status"])
    assertEquals("Temp", unavailable.strings["sensor.s0.name"])
    assertTrue(unavailable.floats["sensor.s0.numeric_state"]!!.isNaN())
  }
}
