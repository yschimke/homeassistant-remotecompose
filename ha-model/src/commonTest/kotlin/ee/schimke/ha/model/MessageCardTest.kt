package ee.schimke.ha.model

import kotlin.test.*
import kotlinx.serialization.json.*

class MessageCardTest {
  private fun card(): CardConfig {
    val raw =
      Json.parseToJsonElement(
          """{"type":"custom:terrazzo-message","inputs":[{"key":"channel","label":"Channel","type":"choice","entity":"input_select.channel"},{"key":"message","label":"Message","type":"multiline"}],"tap_action":{"action":"perform-action","perform_action":"script.send_room_message","data":{"message":"{{input.message}}","channel":"{{input.channel}}"}}}"""
        )
        .jsonObject
    return CardConfig(MESSAGE_CARD_TYPE, raw)
  }

  private fun snapshot(value: String = "matrix") =
    HaSnapshot(
      states =
        mapOf(
          "input_select.channel" to
            EntityState(
              "input_select.channel",
              value,
              buildJsonObject {
                put(
                  "options",
                  JsonArray(listOf(JsonPrimitive("matrix"), JsonPrimitive("telegram"))),
                )
              },
            )
        )
    )

  @Test
  fun typedChoicesAndUnicodeMessageRoundTripThroughExistingActionPayload() {
    val fields = messageInputs(card(), snapshot())
    assertEquals(listOf("matrix", "telegram"), fields[0].options)
    assertTrue(fields[1].multiline)
    val message = "Hello \"room\" 🏡\n{{literal}}"
    val action = messageAction(card(), snapshot("telegram"), mapOf("message" to message))
    assertEquals("send_room_message", action["service"]?.jsonPrimitive?.content)
    assertEquals(message, action["serviceData"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    assertEquals(
      "telegram",
      action["serviceData"]?.jsonObject?.get("channel")?.jsonPrimitive?.content,
    )
  }

  @Test
  fun unsupportedInputsAndUndeclaredChoicesAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      messageAction(card(), snapshot(), mapOf("channel" to "invented"))
    }
    val invalid =
      card().let {
        it.copy(
          raw =
            JsonObject(
              it.raw +
                ("inputs" to
                  JsonArray(
                    listOf(
                      buildJsonObject {
                        put("key", "password")
                        put("type", "password")
                      }
                    )
                  ))
            )
        )
      }
    assertFailsWith<IllegalArgumentException> { messageInputs(invalid, snapshot()) }
  }
}
