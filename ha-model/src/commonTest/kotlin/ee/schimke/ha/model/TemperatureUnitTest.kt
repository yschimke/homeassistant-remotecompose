package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TemperatureUnitTest {

  private fun climate(vararg attrs: Pair<String, String>) =
    EntityState(
      "climate.hall",
      "heat",
      JsonObject(attrs.associate { (key, value) -> key to JsonPrimitive(value) }),
    )

  @Test
  fun theUnitSystemAppliesWhenTheEntityHasNoUnit() {
    assertEquals("°F", HaSnapshot(temperatureUnit = "°F").temperatureUnitFor(climate()))
  }

  @Test
  fun theEntitysOwnUnitWins() {
    val snapshot = HaSnapshot(temperatureUnit = "°F")
    assertEquals("°C", snapshot.temperatureUnitFor(climate("temperature_unit" to "°C")))
    assertEquals("°C", snapshot.temperatureUnitFor(climate("unit_of_measurement" to "°C")))
  }

  @Test
  fun celsiusWithoutAnyUnit() {
    assertEquals("°C", HaSnapshot().temperatureUnitFor(climate()))
    assertEquals("°C", HaSnapshot().temperatureUnitFor(null))
  }
}
