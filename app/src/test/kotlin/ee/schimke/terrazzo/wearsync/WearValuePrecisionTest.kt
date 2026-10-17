package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The watch formats with the phone's precision: it travels with each value. */
class WearValuePrecisionTest {
  private fun entity(id: String, vararg precision: Pair<String, Int>) =
    EntityState(
      id,
      "21.46",
      buildJsonObject {
        put("unit_of_measurement", "°C")
        precision.forEach { (k, v) -> put(k, v) }
      },
    )

  @Test
  fun registryPrecisionWinsThenTheEntitysAttributes() {
    val registry = entity("sensor.a", "suggested_display_precision" to 1)
    val attribute = entity("sensor.b", "display_precision" to 0)
    val suggested = entity("sensor.c", "suggested_display_precision" to 1)
    val none = entity("sensor.d")
    val snapshot =
      HaSnapshot(
        states = listOf(registry, attribute, suggested, none).associateBy { it.entityId },
        devices = mapOf("sensor.a" to DeviceInfo(displayPrecision = 3)),
      )
    assertEquals(3, registry.toEntityValue(snapshot).displayPrecision)
    assertEquals(0, attribute.toEntityValue(snapshot).displayPrecision)
    assertEquals(1, suggested.toEntityValue(snapshot).displayPrecision)
    assertNull(none.toEntityValue(snapshot).displayPrecision)
  }

  @Test
  fun precisionRoundTripsAndOldPayloadsHaveNone() {
    val sent = EntityValue(state = "21.46", unit = "°C", displayPrecision = 0)
    assertEquals(sent, decodeProto<EntityValue>(encodeProto(sent)))
    assertNull(decodeProto<EntityValue>(encodeProto(EntityValue(state = "on")))?.displayPrecision)
  }
}
