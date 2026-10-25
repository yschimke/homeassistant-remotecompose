package ee.schimke.terrazzo.notify

import androidx.core.app.NotificationCompat
import ee.schimke.ha.client.HaPushNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HaNotificationsTest {
  private val context = RuntimeEnvironment.getApplication()
  private val message =
    HaPushNotification(
      message = "Washer done",
      title = "Laundry",
      tag = "washer",
      actions = listOf(HaPushNotification.Action("ACK", "OK")),
    )

  @Test
  fun bridgesWithTheTagAndADismissalIdSharedAcrossDevices() {
    val wear =
      NotificationCompat.WearableExtender(
        HaNotifications.build(context, BASE_URL, message, watchHasApp = false)
      )
    assertEquals(HaNotifications.BRIDGE_TAG, wear.bridgeTag)
    assertEquals("tag:washer", wear.dismissalId)
    assertTrue(wear.actions.isEmpty(), "no watch action without the Wear app")
  }

  @Test
  fun aWatchWithTheAppGetsOpenOnWatch() {
    val wear =
      NotificationCompat.WearableExtender(
        HaNotifications.build(context, BASE_URL, message, watchHasApp = true)
      )
    assertEquals(listOf("Open on watch"), wear.actions.map { it.title.toString() })
  }

  @Test
  fun haActionsBecomeButtons() {
    val n = HaNotifications.build(context, BASE_URL, message, watchHasApp = false)
    assertEquals(listOf("OK"), n.actions.map { it.title.toString() })
    assertEquals("Laundry", n.extras.getString(NotificationCompat.EXTRA_TITLE))
  }

  @Test
  fun theSameTagPostsUnderTheSameId() {
    assertEquals(
      HaNotifications.idOf(message),
      HaNotifications.idOf(message.copy(message = "Washer done again")),
    )
  }

  @Test
  fun aCardRefreshDoesNotAlertAgainButANewMessageDoes() {
    val refresh = HaNotifications.build(context, BASE_URL, message, false, refresh = true)
    val fresh = HaNotifications.build(context, BASE_URL, message, false)
    assertTrue(refresh.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertTrue(fresh.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE == 0)
  }

  private companion object {
    const val BASE_URL = "http://homeassistant.test:8123"
  }
}
