package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.*
import kotlin.test.*
import kotlinx.serialization.json.*

class ConversationTest {
  private val id = "binary_sensor.meshcore_radio_ch_0_messages"

  private fun card(raw: String) = Json.decodeFromString(CardConfig.serializer(), raw)

  @Test
  fun messageSensorsBecomeChatsButActivityAndMixedLogsDoNot() {
    assertEquals(id, conversationEntity(card("""{"type":"tile","entity":"$id"}""")))
    assertEquals(id, conversationEntity(card("""{"type":"logbook","entities":["$id"]}""")))
    assertNull(
      conversationEntity(card("""{"type":"tile","entity":"binary_sensor.meshcore_room_contact"}"""))
    )
    assertNull(conversationEntity(card("""{"type":"logbook","entities":["$id","light.room"]}""")))
  }

  @Test
  fun historyExcludesStateChangesOtherConversationsAndMalformedRows() {
    val rows =
      Json.parseToJsonElement(
          """[
      {"entity_id":"$id","when":2,"message":"<public> Room: Temperature: 22°C"},
      {"entity_id":"$id","when":1,"message":"Operator: Check the room"},
      {"entity_id":"$id","when":1,"message":"Operator: Check the room"},
      {"entity_id":"$id","when":3,"state":"Active"},
      {"entity_id":"binary_sensor.meshcore_other_messages","when":4,"message":"Private: Excluded"},
      {"entity_id":"$id","message":"No timestamp"}
    ]"""
        )
        .jsonArray
    val result = conversationMessages(rows, id)
    assertEquals(listOf("Operator", "Room"), result.map { it.sender })
    assertEquals("Temperature: 22°C", result.last().text)
    assertTrue(conversationMessages(JsonArray(emptyList()), id).isEmpty())
  }

  @Test
  fun repliesUseOnlyExplicitRecipientsAndPreserveUnicodeByteLimits() {
    val channel = EntityState(id, "Active", buildJsonObject { put("channel_index", "2") })
    val request = assertNotNull(conversationSend(channel, "companion-one", "Hello"))
    assertEquals("send_channel_message", request.service)
    assertEquals(JsonPrimitive(2), request.data["channel_idx"])
    assertEquals(JsonPrimitive("companion-one"), request.data["entry_id"])
    assertNull(conversationSend(channel, null, ""))
    assertNull(conversationSend(channel, null, "🙂".repeat(31)))
    assertNull(conversationSend(channel.copy(state = "unavailable"), null, "Hello"))
    assertNull(conversationSend(channel.copy(attributes = JsonObject(emptyMap())), null, "Hello"))
    val direct = channel.copy(attributes = buildJsonObject { put("public_key", "aabbccddeeff") })
    assertEquals("send_message", conversationSend(direct, null, "Hello")?.service)
  }
}
