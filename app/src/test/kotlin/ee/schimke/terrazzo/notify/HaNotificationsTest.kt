package ee.schimke.terrazzo.notify

import androidx.core.app.NotificationCompat
import ee.schimke.ha.client.HaPushNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
  fun aWatchWithTheAppGetsItsOwnCopyNotTheBridgedOne() {
    val n = HaNotifications.build(context, BASE_URL, message, watchHasApp = true)
    assertTrue(NotificationCompat.getLocalOnly(n), "the Wear app posts its own; nothing bridges")
    assertNotNull(n.deleteIntent, "dismissing the phone's copy takes the watch's down")
    assertTrue(NotificationCompat.WearableExtender(n).actions.isEmpty())
  }

  @Test
  fun withoutTheWearAppItBridges() {
    val n = HaNotifications.build(context, BASE_URL, message, watchHasApp = false)
    assertFalse(NotificationCompat.getLocalOnly(n))
    assertNull(n.deleteIntent)
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

  @Test
  fun aCardIsThePhonesExpandedBodyOnlyTheWatchKeepsTheStandardFields() {
    val card = android.widget.RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
    val n = HaNotifications.build(context, BASE_URL, message, watchHasApp = true, card = card)
    assertTrue(n.bigContentView != null, "the phone's expanded body is the card")
    // Big text style, not a custom-view style: what a watch (or any non-RemoteViews surface) shows.
    assertEquals(
      NotificationCompat.BigTextStyle::class.java.name,
      NotificationCompat.Style.extractStyleFromNotification(n)?.javaClass?.name,
    )
    assertEquals(
      "Washer done",
      n.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString(),
    )
    assertEquals(listOf("OK"), n.actions.map { it.title.toString() })
  }

  private companion object {
    const val BASE_URL = "http://homeassistant.test:8123"
  }
}
