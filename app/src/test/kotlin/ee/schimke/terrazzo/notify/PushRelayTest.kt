package ee.schimke.terrazzo.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushRelayTest {
  @Test
  fun aRelayedMessageParsesAsTheWebsocketsWould() {
    val n =
      PushRelay.fromRelay(
        mapOf(
          "webhook_id" to "abc",
          "message" to "Washer done",
          "title" to "Laundry",
          "data" to
            """{"tag":"washer","entity_id":"switch.washer","actions":[{"action":"ACK","title":"OK"}]}""",
        )
      )!!
    assertEquals("Washer done", n.message)
    assertEquals("Laundry", n.title)
    assertEquals("washer", n.tag)
    assertEquals("switch.washer", n.entityId)
    assertEquals(listOf("ACK"), n.actions.map { it.action })
    assertEquals("tag:washer", n.dedupeKey)
  }

  @Test
  fun badDataIsIgnoredAndNoMessageIsNoNotification() {
    assertEquals("Hi", PushRelay.fromRelay(mapOf("message" to "Hi", "data" to "not json"))?.message)
    assertNull(PushRelay.fromRelay(mapOf("title" to "Only a title")))
  }
}
