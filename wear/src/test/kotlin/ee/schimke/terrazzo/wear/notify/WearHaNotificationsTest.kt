package ee.schimke.terrazzo.wear.notify

import androidx.core.app.NotificationCompat
import ee.schimke.terrazzo.wearsync.proto.WearNotification
import ee.schimke.terrazzo.wearsync.proto.WearNotificationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearHaNotificationsTest {
  private val context = RuntimeEnvironment.getApplication()
  private val payload =
    WearNotification(
      id = 7,
      baseUrl = "http://homeassistant.test:8123",
      title = "Kitchen",
      message = "Left on",
      tag = "kitchen",
      entityName = "Kitchen light",
      entityState = "On",
      toggleEntityId = "light.kitchen",
      actions = listOf(WearNotificationAction("ACK", "OK")),
      postedAtMs = 1_000,
    )

  @Test
  fun standardFieldsWithTheEntityAToggleAndHaButtons() {
    val n = WearHaNotifications.build(context, payload)
    assertEquals("Kitchen", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
    assertEquals(
      "Left on",
      n.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString(),
    )
    assertEquals(
      "Kitchen light · On",
      n.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT).toString(),
    )
    assertEquals(listOf("Turn off", "OK"), n.actions.map { it.title.toString() })
    assertNotNull("a dismissal tells the phone", n.deleteIntent)
    assertTrue(n.contentView == null && n.bigContentView == null)
  }

  @Test
  fun anUpdateDoesNotAlertAgain() {
    val update = WearHaNotifications.build(context, payload.copy(update = true))
    val fresh = WearHaNotifications.build(context, payload)
    assertTrue(update.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertTrue(fresh.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE == 0)
  }

  @Test
  fun withoutAnEntityNoSubTextOrToggle() {
    val n =
      WearHaNotifications.build(
        context,
        payload.copy(entityName = "", entityState = "", toggleEntityId = ""),
      )
    assertEquals(null, n.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT))
    assertEquals(listOf("OK"), n.actions.map { it.title.toString() })
  }
}
