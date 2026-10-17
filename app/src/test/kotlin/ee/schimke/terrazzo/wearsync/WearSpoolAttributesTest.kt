package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Bambu spool's colour and remaining filament reach the watch, so its spool card draws the
 * spool's colour and how full it is rather than a grey, unknown spool.
 */
class WearSpoolAttributesTest {
  private val tray =
    EntityState(
      "sensor.p1s_ams_1_tray_2",
      "PLA",
      buildJsonObject {
        put("friendly_name", "AMS 1 Tray 2")
        put("color", "#FF6A13FF")
        put("remain", 63)
        put("remain_enabled", true)
        put("active", false)
        put("tray_uuid", "00000000000000000000000000000000")
        put("k", 0.02)
      },
    )

  @Test
  fun aSpoolsColourAndRemainingFilamentTravelToTheWatch() {
    val value = tray.toEntityValue(HaSnapshot(states = mapOf(tray.entityId to tray)))
    assertEquals(
      mapOf(
        "color" to "#FF6A13FF",
        "remain" to "63",
        "remain_enabled" to "true",
        "active" to "false",
      ),
      value.attributes,
      "only the attributes a watch render reads, not the tray's other details",
    )
    assertEquals(value, decodeProto<EntityValue>(encodeProto(value)))
  }

  @Test
  fun anEntityWithoutThemSendsNoAttributes() {
    val lamp = EntityState("light.lamp", "on", buildJsonObject { put("friendly_name", "Lamp") })
    assertEquals(emptyMap(), lamp.toEntityValue(HaSnapshot()).attributes)
  }
}
