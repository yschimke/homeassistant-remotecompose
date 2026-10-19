package ee.schimke.ha.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class InstanceConfigTest {

  private fun config(json: String) = instanceConfigOf(Json.parseToJsonElement(json).jsonObject)

  @Test
  fun readsTheUnitSystemsTemperatureUnit() {
    val config =
      config(
        """
        {
          "version": "2026.10.2",
          "location_name": "Home",
          "internal_url": null,
          "external_url": "https://example.ui.nabu.casa",
          "unit_system": {"length": "mi", "mass": "lb", "temperature": "°F", "volume": "gal"}
        }
        """
      )
    assertEquals("°F", config.temperatureUnit)
    assertEquals("2026.10.2", config.version)
    assertNull(config.internalUrl)
    assertEquals("https://example.ui.nabu.casa", config.externalUrl)
  }

  @Test
  fun aConfigWithoutAUnitSystemHasNoTemperatureUnit() {
    assertNull(config("""{"version": "2026.10.2"}""").temperatureUnit)
  }
}
