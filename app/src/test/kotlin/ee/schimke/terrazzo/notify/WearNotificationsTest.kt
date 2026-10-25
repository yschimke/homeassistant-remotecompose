package ee.schimke.terrazzo.notify

import ee.schimke.ha.client.HaPushNotification
import ee.schimke.ha.model.EntityState
import ee.schimke.terrazzo.wearsync.proto.WearNotificationAction
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class WearNotificationsTest {
  private val message =
    HaPushNotification(
      message = "Left on",
      title = "Kitchen",
      tag = "kitchen",
      entityId = "light.kitchen",
      actions = listOf(HaPushNotification.Action("ACK", "OK")),
    )

  @Test
  fun aToggleableEntityGivesTheWatchItsNameStateAndAToggle() {
    val light =
      EntityState(
        "light.kitchen",
        "on",
        buildJsonObject { put("friendly_name", JsonPrimitive("Kitchen light")) },
      )
    val payload = WearNotifications.payload(7, BASE_URL, message, light, nowMs = 1_000)
    assertEquals("Kitchen", payload.title)
    assertEquals("Left on", payload.message)
    assertEquals("kitchen", payload.tag)
    assertEquals("Kitchen light", payload.entityName)
    assertEquals("light.kitchen", payload.toggleEntityId)
    assertEquals(listOf(WearNotificationAction("ACK", "OK")), payload.actions)
    assertEquals(false, payload.update)
  }

  @Test
  fun aSensorHasNoToggleAndAMessageWithoutAnEntityNoSubject() {
    val sensor = EntityState("sensor.washer", "12")
    assertEquals("", WearNotifications.payload(1, BASE_URL, message, sensor).toggleEntityId)
    val plain = WearNotifications.payload(1, BASE_URL, message, null)
    assertEquals("", plain.entityName)
    assertEquals("", plain.entityState)
    assertEquals("", plain.toggleEntityId)
  }

  @Test
  fun aButtonThatOpensAUriStaysOnThePhone() {
    val withLink =
      message.copy(
        actions = message.actions + HaPushNotification.Action("URI", "Open", uri = "/lovelace/0")
      )
    assertEquals(
      listOf(WearNotificationAction("ACK", "OK")),
      WearNotifications.payload(1, BASE_URL, withLink, null).actions,
    )
  }

  @Test
  fun notificationPathsRoundTripEveryId() {
    for (id in listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, "tag:washer".hashCode())) {
      assertEquals(id, WearSyncPaths.notificationId(WearSyncPaths.notificationPath(id)))
    }
    assertNull(WearSyncPaths.notificationId("/wear/pinned"))
    assertNull(WearSyncPaths.notificationId(WearSyncPaths.NOTIFICATION_PREFIX + "zz"))
  }

  private companion object {
    const val BASE_URL = "http://homeassistant.test:8123"
  }
}
