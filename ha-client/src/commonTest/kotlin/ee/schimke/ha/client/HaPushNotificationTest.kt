package ee.schimke.ha.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class HaPushNotificationTest {
  private fun parse(json: String) =
    HaPushNotification.parse(Json.parseToJsonElement(json).jsonObject)

  @Test
  fun parsesTheCompanionAppKeys() {
    val n =
      parse(
        """{"message":"Door open","title":"Front","hass_confirm_id":"c1",
          "data":{"tag":"door","group":"security","clickAction":"/lovelace/security",
          "entity_id":"binary_sensor.front_door",
          "actions":[{"action":"CLOSE","title":"Close it"},{"action":"URI","title":"Camera","uri":"/cam"}]}}"""
      )!!
    assertEquals("Door open", n.message)
    assertEquals("Front", n.title)
    assertEquals("door", n.tag)
    assertEquals("security", n.group)
    assertEquals("/lovelace/security", n.url)
    assertEquals("binary_sensor.front_door", n.entityId)
    assertEquals("c1", n.confirmId)
    assertEquals(
      listOf(
        HaPushNotification.Action("CLOSE", "Close it"),
        HaPushNotification.Action("URI", "Camera", "/cam"),
      ),
      n.actions,
    )
  }

  @Test
  fun aMessageIsRequired() {
    assertNull(parse("""{"title":"No body"}"""))
  }

  @Test
  fun theTagIsTheDedupeKeySoAResendReplacesTheFirst() {
    val a = parse("""{"message":"one","data":{"tag":"t"}}""")!!
    val b = parse("""{"message":"two","data":{"tag":"t"}}""")!!
    assertEquals(a.dedupeKey, b.dedupeKey)
  }

  @Test
  fun untaggedMessagesDedupeOnTheirContent() {
    val a = parse("""{"message":"same","title":"x","hass_confirm_id":"1"}""")!!
    val b = parse("""{"message":"same","title":"x","hass_confirm_id":"2"}""")!!
    val c = parse("""{"message":"other","title":"x"}""")!!
    assertEquals(a.dedupeKey, b.dedupeKey)
    assertNotEquals(a.dedupeKey, c.dedupeKey)
  }

  @Test
  fun clearNotificationIsRecognised() {
    assertTrue(parse("""{"message":"clear_notification","data":{"tag":"t"}}""")!!.isClear)
  }
}
