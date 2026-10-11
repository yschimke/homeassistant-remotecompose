package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

  private fun snapshotOf(vararg states: EntityState) =
    HaSnapshot(states = states.associateBy { it.entityId })

  private fun state(id: String, value: String = "on", vararg attrs: Pair<String, String>) =
    EntityState(id, value, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  @Test
  fun shapeHoldsStructureOnly() {
    val snapshot =
      snapshotOf(state("light.kitchen", "on", "friendly_name" to "Kitchen"), state("light.hall"))
    assertEquals("domain=light", CardDesignSlots.shapeOf(tile("light.kitchen"), snapshot))
    // Names, states and friendly names never change the shape.
    assertEquals(
      CardDesignSlots.shapeOf(tile("light.kitchen"), snapshot),
      CardDesignSlots.shapeOf(tile("light.hall", "name" to "Hall"), snapshot),
    )
  }

  @Test
  fun shapeCarriesWhatChangesTheTilesLook() {
    val snapshot =
      snapshotOf(
        state("binary_sensor.door", "off", "device_class" to "door"),
        state("sensor.temp", "21", "device_class" to "temperature", "icon" to "MDI:Thermometer"),
        state("switch.fan", "on", "device_class" to "outlet"),
      )
    assertEquals(
      "domain=binary_sensor;device_class=door",
      CardDesignSlots.shapeOf(tile("binary_sensor.door"), snapshot),
    )
    assertEquals(
      "domain=sensor;device_class=temperature;icon=mdi:thermometer",
      CardDesignSlots.shapeOf(tile("sensor.temp"), snapshot),
    )
    // A switch's device_class doesn't change its tile, so it stays out of the shape.
    assertEquals("domain=switch", CardDesignSlots.shapeOf(tile("switch.fan"), snapshot))
    assertEquals(
      mapOf("domain" to "sensor", "device_class" to "temperature", "icon" to "mdi:thermometer"),
      CardDesignSlots.parseShape("domain=sensor;device_class=temperature;icon=mdi:thermometer"),
    )
  }

  @Test
  fun unmodelledConfigHasNoShape() {
    val snapshot = snapshotOf(state("light.kitchen"), state("climate.living", "heat"))
    assertNull(CardDesignSlots.shapeOf(tile("light.kitchen", "icon" to "mdi:lamp"), snapshot))
    assertNull(CardDesignSlots.shapeOf(tile("climate.living"), snapshot))
    assertNull(CardDesignSlots.shapeOf(CardConfig(type = "gauge"), snapshot))
    // Not loaded yet: the local converter renders the missing entity.
    assertNull(CardDesignSlots.shapeOf(tile("light.unknown"), snapshot))
  }

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
