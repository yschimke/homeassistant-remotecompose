package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
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
