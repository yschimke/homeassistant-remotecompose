package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.model.*
import kotlin.test.*
import kotlinx.serialization.json.*

class WearInputActionTest {
  private val raw =
    """{"type":"custom:terrazzo-message","inputs":[{"key":"message","label":"Message"}],"tap_action":{"action":"perform-action","perform_action":"script.send_message","data":{"message":"{{input.message}}"}}}"""
  private val card = CardConfig(MESSAGE_CARD_TYPE, Json.parseToJsonElement(raw).jsonObject)

  @Test
  fun watchValuesUseThePhonesPublishedActionAndPreserveUnicode() {
    val action =
      wearInputAction(
        raw,
        mapOf("message" to "Hello 🏡\nroom"),
        mapOf(raw to card),
        HaSnapshot(),
        readOnly = false,
      )
    assertEquals("script", action.domain)
    assertEquals("send_message", action.service)
    assertEquals("Hello 🏡\nroom", action.serviceData["message"]?.jsonPrimitive?.content)
  }

  @Test
  fun demoUnknownAndModifiedCardRequestsCannotCallServices() {
    assertFailsWith<IllegalArgumentException> {
      wearInputAction(raw, emptyMap(), mapOf(raw to card), HaSnapshot(), readOnly = true)
    }
    assertFailsWith<IllegalArgumentException> {
      wearInputAction(raw, emptyMap(), emptyMap(), HaSnapshot(), readOnly = false)
    }
    assertFailsWith<IllegalArgumentException> {
      wearInputAction(
        raw.replace("send_message", "other_service"),
        emptyMap(),
        mapOf(raw to card),
        HaSnapshot(),
        readOnly = false,
      )
    }
  }
}
