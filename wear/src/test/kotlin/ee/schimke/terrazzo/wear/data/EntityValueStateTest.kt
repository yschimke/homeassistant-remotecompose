package ee.schimke.terrazzo.wear.data

import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.displayPrecisionOf
import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wear text reads exactly what the phone's cards show: the shared `formatState`, not raw state. */
class EntityValueStateTest {

  @Test
  fun wearTextUsesTheSharedFormatter() {
    val cases =
      listOf(
        Triple("light.kitchen", EntityValue(state = "on"), "On"),
        Triple("lock.front_door", EntityValue(state = "locked"), "Locked"),
        Triple(
          "sensor.living_room",
          EntityValue(state = "21.46", unit = "°C", deviceClass = "temperature"),
          "21.5 °C",
        ),
        Triple(
          "binary_sensor.back_door",
          EntityValue(state = "on", deviceClass = "door"),
          "Open",
        ),
        Triple("sensor.x", EntityValue(state = "unknown"), "Unknown"),
      )
    for ((id, value, expected) in cases) {
      assertEquals(id, expected, value.displayState(id))
      assertEquals(id, formatState(value.toEntityState(id)), value.displayState(id))
    }
  }

  /**
   * Phone formats X, watch shows X: the phone resolves the precision (registry `dp`, else the
   * entity's attributes), it crosses the wire in [EntityValue.displayPrecision], and the watch's
   * `formatState` rounds to it.
   */
  @OptIn(ExperimentalSerializationApi::class)
  @Test
  fun precisionSurvivesTheWireAndMatchesThePhone() {
    val registry =
      EntityState("sensor.t", "21.46", buildJsonObject { put("unit_of_measurement", "°C") })
    val suggested =
      EntityState(
        "sensor.u",
        "21",
        buildJsonObject {
          put("unit_of_measurement", "°C")
          put("suggested_display_precision", 1)
        },
      )
    val plain =
      EntityState("sensor.v", "21.46", buildJsonObject { put("unit_of_measurement", "°C") })
    val snapshot =
      HaSnapshot(
        states = listOf(registry, suggested, plain).associateBy { it.entityId },
        devices = mapOf("sensor.t" to DeviceInfo(displayPrecision = 2)),
      )
    for (entity in snapshot.states.values) {
      val phone = formatState(entity, snapshot)
      // What MobileWearSyncManager sends (the fields Wear reads).
      val sent =
        EntityValue(
          state = entity.state,
          unit = "°C",
          displayPrecision = displayPrecisionOf(entity, snapshot),
        )
      val received =
        ProtoBuf.decodeFromByteArray(
          EntityValue.serializer(),
          ProtoBuf.encodeToByteArray(EntityValue.serializer(), sent),
        )
      assertEquals(entity.entityId, phone, received.displayState(entity.entityId))
    }
    assertEquals("21.46 °C", formatState(registry, snapshot))
    assertEquals("21.0 °C", formatState(suggested, snapshot))
    assertEquals("21.5 °C", formatState(plain, snapshot))
  }

  /** A spool's colour and remaining filament reach the converters as the attributes they read. */
  @Test
  fun spoolAttributesBecomeTheEntitysAttributes() {
    val value =
      EntityValue(
        state = "PLA",
        attributes = mapOf("color" to "#FF6A13FF", "remain" to "63", "active" to "true"),
      )
    val attrs = value.toEntityState("sensor.p1s_ams_1_tray_2").attributes
    assertEquals("#FF6A13FF", attrs["color"]?.jsonPrimitive?.content)
    assertEquals(63.0, attrs["remain"]?.jsonPrimitive?.content?.toDouble())
    assertEquals(true, attrs["active"]?.jsonPrimitive?.content?.toBooleanStrict())
  }
}
