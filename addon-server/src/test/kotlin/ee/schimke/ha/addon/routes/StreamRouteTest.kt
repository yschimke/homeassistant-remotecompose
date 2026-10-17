package ee.schimke.ha.addon.routes

import ee.schimke.ha.addon.bridge.registryDisplayPrecisions
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cardSnapshotBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class StreamRouteTest {
  @Test
  fun stateBindingsKeepUnitsOnLiveUpdates() {
    val before =
      EntityState(
        "sensor.temp",
        "21.500",
        buildJsonObject { put("unit_of_measurement", "°C") },
      )
    val after = before.copy(state = "23.125")

    val initial = buildJsonObject { putStateBindings(before) }
    val update = buildJsonObject { putStateBindings(after) }

    assertEquals("21.5 °C", initial["sensor.temp.state"]?.jsonPrimitive?.content)
    assertEquals("23.1 °C", update["sensor.temp.state"]?.jsonPrimitive?.content)
    assertEquals("23.125", update["sensor.temp.numeric_state"]?.jsonPrimitive?.content)
  }

  @Test
  fun stateBindingsUseTheRegistryPrecisionTheAppAuthorsWith() {
    // What the app bakes and pushes: formatState(entity, snapshot), dp from the registry.
    val entity =
      EntityState("sensor.temp", "21.46", buildJsonObject { put("unit_of_measurement", "°C") })
    val registry =
      Json.parseToJsonElement(
          """[{"ei": "sensor.temp", "dp": 2}, {"ei": "sensor.bare"}, {"dp": 1}]"""
        )
        .jsonArray
    val precisions = registryDisplayPrecisions(registry)
    assertEquals(mapOf("sensor.temp" to 2), precisions)
    val snapshot =
      HaSnapshot(
        states = mapOf(entity.entityId to entity),
        devices = mapOf(entity.entityId to DeviceInfo(displayPrecision = 2)),
      )
    val app = cardSnapshotBindings(setOf(entity.entityId), snapshot).strings["sensor.temp.state"]

    val addon = buildJsonObject { putStateBindings(entity, precisions[entity.entityId]) }

    assertEquals("21.46 °C", app)
    assertEquals(app, addon["sensor.temp.state"]?.jsonPrimitive?.content)
  }

  @Test
  fun stateBindingsCarryAvailability() {
    val light = EntityState("light.kitchen", "unavailable")
    val unavailable = buildJsonObject { putStateBindings(light) }
    val back = buildJsonObject { putStateBindings(light.copy(state = "off")) }
    // `is_on` alone can't tell unavailable from off; an on/off icon pair reads this too.
    assertEquals("false", unavailable["light.kitchen.is_available"]?.jsonPrimitive?.content)
    assertEquals("true", back["light.kitchen.is_available"]?.jsonPrimitive?.content)
  }
}
