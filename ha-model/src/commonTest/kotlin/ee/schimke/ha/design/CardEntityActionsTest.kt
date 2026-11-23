package ee.schimke.ha.design

import ee.schimke.ha.design.CardEntityActions.ServiceCall
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [CardEntityActions]: a design's named action resolves against the card's own entity, to its own
 * domain's service only. `embed/test/entity-actions.test.mjs` holds the browser card's port to the
 * same cases.
 */
class CardEntityActionsTest {
  private fun snapshot(
    entityId: String,
    state: String = "closed",
    unitSystem: String? = null,
    vararg attributes: Pair<String, Number>,
  ) =
    HaSnapshot(
      states =
        mapOf(
          entityId to
            EntityState(
              entityId,
              state,
              JsonObject(attributes.associate { (k, v) -> k to JsonPrimitive(v) }),
            ),
        ),
      temperatureUnit = unitSystem,
    )

  @Test
  fun aGarageDoorsButtonsEachSendTheirOwnService() {
    val door = snapshot("cover.garage", "closed", null, "supported_features" to 15)
    for (service in listOf("open_cover", "stop_cover", "close_cover")) {
      assertEquals(
        ServiceCall("cover", service, "cover.garage"),
        CardEntityActions.resolve("cover.garage", service, door),
      )
    }
  }

  @Test
  fun aCoverThatCantStopSendsNoStop() {
    // supported_features 3: open and close, no stop (bit 8).
    val gate = snapshot("cover.gate", "open", null, "supported_features" to 3)
    assertNull(CardEntityActions.resolve("cover.gate", "stop_cover", gate))
    assertEquals(
      ServiceCall("cover", "close_cover", "cover.gate"),
      CardEntityActions.resolve("cover.gate", "close_cover", gate),
    )
    // A cover that reports no features at all: every one, as Home Assistant's own card offers.
    assertEquals(
      ServiceCall("cover", "stop_cover", "cover.x"),
      CardEntityActions.resolve("cover.x", "stop_cover", snapshot("cover.x")),
    )
  }

  @Test
  fun onlyTheEntitysOwnDomainsActions() {
    assertNull(CardEntityActions.resolve("lock.front", "unlock", snapshot("lock.front")))
    assertNull(CardEntityActions.resolve("light.kitchen", "open_cover", snapshot("light.kitchen")))
    assertNull(CardEntityActions.resolve("cover.garage", "set_cover_position", null))
    assertEquals(
      ServiceCall("light", "turn_off", "light.kitchen"),
      CardEntityActions.resolve("light.kitchen", "turn_off", null),
    )
  }

  @Test
  fun aThermostatStepsItsSetPointWithinItsRange() {
    val celsius =
      snapshot(
        "climate.hall",
        "heat",
        null,
        "temperature" to 21,
        "min_temp" to 7,
        "max_temp" to 21.2,
      )
    assertEquals(
      ServiceCall(
        "climate",
        "set_temperature",
        "climate.hall",
        JsonObject(mapOf("temperature" to JsonPrimitive(20.5))),
      ),
      CardEntityActions.resolve("climate.hall", "temperature_down", celsius),
    )
    // Up by 0.5 would pass max_temp: it stops there.
    assertEquals(
      JsonPrimitive(21.2),
      CardEntityActions.resolve("climate.hall", "temperature_up", celsius)!!.data["temperature"],
    )
    // A °F home steps by a whole degree unless the entity says otherwise.
    val fahrenheit = snapshot("climate.hall", "heat", "°F", "temperature" to 70, "max_temp" to 90)
    assertEquals(
      JsonPrimitive(71),
      CardEntityActions.resolve("climate.hall", "temperature_up", fahrenheit)!!.data["temperature"],
    )
    val stepped =
      snapshot("climate.hall", "heat", null, "temperature" to 20, "target_temp_step" to 0.1)
    assertEquals(
      JsonPrimitive(20.1),
      CardEntityActions.resolve("climate.hall", "temperature_up", stepped)!!.data["temperature"],
    )
    // No set point (off, or holding a range): nothing to step.
    assertNull(
      CardEntityActions.resolve("climate.hall", "temperature_up", snapshot("climate.hall", "off")),
    )
  }
}
