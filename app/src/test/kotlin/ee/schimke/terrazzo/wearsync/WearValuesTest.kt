package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The watch is sent the values its cards read, not every entity Home Assistant has. */
class WearValuesTest {
  private fun entity(id: String, state: String = "on") =
    EntityState(id, state, JsonObject(mapOf("friendly_name" to JsonPrimitive("Entity $id"))))

  @Test
  fun everyEntityACardNamesCountsWhereverItSits() {
    val cards =
      listOf(
        """{"type":"tile","entity":"light.kitchen"}""",
        // A resolved Bambu card names its sensors under its own keys.
        """{"type":"custom:ha-bambulab-print_status-card","printer":"abc123",
          "print_status":"sensor.p2s_print_status","online":"binary_sensor.p2s_online"}""",
        """{"type":"markdown","content":"It is {{ states('sensor.outside_temp') }} °C"}""",
        """{"type":"vertical-stack","cards":[{"type":"entities","entities":["switch.fan",
          {"entity":"lock.front_door"}]}]}""",
      )
    assertEquals(
      setOf(
        "light.kitchen",
        "sensor.p2s_print_status",
        "binary_sensor.p2s_online",
        "sensor.outside_temp",
        "switch.fan",
        "lock.front_door",
      ),
      wearEntityIds(cards),
    )
  }

  @Test
  fun onlyTheCardsEntitiesAreSentSoALargeHomeStillFitsOneDataItem() {
    // A large home: thousands of entities, far more than one ~100 KB DataItem holds.
    val states =
      (0 until 5_000).associate { "sensor.entity_$it" to entity("sensor.entity_$it", "12.5") } +
        mapOf("binary_sensor.p2s_online" to entity("binary_sensor.p2s_online"))
    val snapshot = HaSnapshot(states = states)
    val everything = LiveValues(values = wearValues(snapshot, states.keys))
    assertTrue(encodeProto(everything).size > 100_000, "the whole home outgrows a DataItem")

    val ids =
      wearEntityIds(listOf("""{"entity":"binary_sensor.p2s_online"}""", """{"entity":"a.b"}"""))
    val sent = wearValues(snapshot, ids)
    assertEquals(setOf("binary_sensor.p2s_online"), sent.keys, "unknown tokens match nothing")
    assertEquals("on", sent.getValue("binary_sensor.p2s_online").state)
    assertTrue(encodeProto(LiveValues(values = sent)).size < 1_000)
  }
}
