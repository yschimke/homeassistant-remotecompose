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
        """{"type":"markdown","content":"It is {{ states('sensor.outside_temp') }} °C"}""",
        """{"type":"vertical-stack","cards":[{"type":"entities","entities":["switch.fan",
          {"entity":"lock.front_door"}]}]}""",
      )
    assertEquals(
      setOf(
        "light.kitchen",
        "sensor.outside_temp",
        "switch.fan",
        "lock.front_door",
      ),
      wearEntityIds(cards, HaSnapshot()),
    )
  }

  /** Two printers' entities, and an unrelated sensor. */
  private val printers =
    HaSnapshot(
      states =
        listOf(
            "sensor.p2s_sn2_print_status",
            "sensor.p2s_sn2_print_progress",
            "binary_sensor.p2s_sn2_online",
            "sensor.a1_mini_print_status",
            "sensor.outside_temp",
          )
          .associateWith { entity(it) }
    )

  @Test
  fun aBambuCardGetsItsPrintersEntitiesWhetherOrNotItWasResolvedToAPrefix() {
    val printerEntities =
      setOf(
        "sensor.p2s_sn2_print_status",
        "sensor.p2s_sn2_print_progress",
        "binary_sensor.p2s_sn2_online",
        "sensor.a1_mini_print_status",
      )
    // Resolved on the phone: the card names only a prefix; the converter builds the sensor ids.
    val resolved =
      """{"type":"custom:ha-bambulab-print_status-card","printer":"abc123",
        "entity_prefix":"p2s_sn2"}"""
    // Unresolved (the watch finds its printer from the printers' entities), and nested in a stack.
    val nested =
      """{"type":"vertical-stack","cards":[{"type":"custom:ha-bambulab-print_status-card"}]}"""
    for (card in listOf(resolved, nested)) {
      assertTrue(wearEntityIds(listOf(card), printers).containsAll(printerEntities), card)
    }
    assertEquals(
      setOf("sensor.outside_temp"),
      wearEntityIds(listOf("""{"type":"tile","entity":"sensor.outside_temp"}"""), printers),
      "no Bambu card, no printer entities",
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
      wearEntityIds(
        listOf("""{"entity":"binary_sensor.p2s_online"}""", """{"entity":"a.b"}"""),
        snapshot,
      )
    val sent = wearValues(snapshot, ids)
    assertEquals(setOf("binary_sensor.p2s_online"), sent.keys, "unknown tokens match nothing")
    assertEquals("on", sent.getValue("binary_sensor.p2s_online").state)
    assertTrue(encodeProto(LiveValues(values = sent)).size < 1_000)
  }
}
