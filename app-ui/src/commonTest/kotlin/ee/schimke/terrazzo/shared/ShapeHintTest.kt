package ee.schimke.terrazzo.shared

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [shapeHint]: the observable shape a converter-less card sends so the design server can pin it.
 */
class ShapeHintTest {
  private fun tile(entityId: String, cardIcon: String? = null) =
    CardConfig(
      "tile",
      buildJsonObject {
        put("type", "tile")
        put("entity", entityId)
        if (cardIcon != null) put("icon", cardIcon)
      },
    )

  private fun state(entityId: String, vararg attrs: Pair<String, String>) =
    EntityState(entityId, "on", buildJsonObject { attrs.forEach { (k, v) -> put(k, v) } })

  private fun snapshot(vararg states: EntityState) =
    HaSnapshot(states = states.associateBy { it.entityId })

  @Test
  fun aDoorBinarySensorCarriesItsDeviceClass() {
    assertEquals(
      buildJsonObject { put("device_class", "door") },
      shapeHint(
        tile("binary_sensor.front_door"),
        snapshot(state("binary_sensor.front_door", "device_class" to "door")),
      ),
    )
  }

  @Test
  fun aTemperatureSensorCarriesDeviceClassAndUnit() {
    assertEquals(
      buildJsonObject {
        put("device_class", "temperature")
        put("unit", "°C")
      },
      shapeHint(
        tile("sensor.temp"),
        snapshot(
          state("sensor.temp", "device_class" to "temperature", "unit_of_measurement" to "°C")
        ),
      ),
    )
  }

  @Test
  fun anIconIsNormalisedAndTheCardIconKept() {
    assertEquals(
      buildJsonObject {
        put("icon", "mdi:thermometer")
        put("card_icon", "mdi:Custom")
      },
      shapeHint(
        tile("sensor.temp", cardIcon = "mdi:Custom"),
        snapshot(state("sensor.temp", "icon" to "MDI:Thermometer")),
      ),
    )
  }

  @Test
  fun aSwitchCarriesNoDeviceClass() {
    // device_class only shapes sensor / binary_sensor tiles; other domains ignore it.
    assertEquals(
      JsonObject(emptyMap()),
      shapeHint(tile("switch.fan"), snapshot(state("switch.fan", "device_class" to "outlet"))),
    )
  }

  @Test
  fun anExactKeyKeepsItsHintOutOfTheCacheKey() {
    // The card reads observable attributes, so its shape hint is non-empty...
    val card = tile("sensor.temp")
    val snapshot =
      snapshot(state("sensor.temp", "device_class" to "temperature", "unit_of_measurement" to "°C"))
    assertTrue(shapeHint(card, snapshot).isNotEmpty())
    // ...but with an exact key the server is asked by key, so the cache hint is empty: two such
    // cards must share one cache entry instead of resolving the same design twice.
    val exact =
      DesignKey(
        cardType = "tile",
        surface = DesignSurface.MobileApp,
        rcApiLevel = 8,
        shape = "device_class=temperature;domain=sensor;unit=°C",
        theme = "baked:Material3:light",
      )
    assertEquals(JsonObject(emptyMap()), cacheShapeHint(exact, card, snapshot))
  }

  @Test
  fun withoutAnExactKeyTheCacheKeepsTheHint() {
    val card = tile("sensor.temp")
    val snapshot =
      snapshot(state("sensor.temp", "device_class" to "temperature", "unit_of_measurement" to "°C"))
    assertEquals(shapeHint(card, snapshot), cacheShapeHint(null, card, snapshot))
  }

  @Test
  fun aMultiSlotCardHasNoHint() {
    val entities =
      CardConfig(
        "entities",
        buildJsonObject {
          put("type", "entities")
          put(
            "entities",
            buildJsonArray {
              add("light.a")
              add("light.b")
            },
          )
        },
      )
    assertEquals(
      JsonObject(emptyMap()),
      shapeHint(entities, snapshot(state("light.a"), state("light.b"))),
    )
  }
}
