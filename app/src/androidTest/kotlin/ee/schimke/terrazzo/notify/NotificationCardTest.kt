package ee.schimke.terrazzo.notify

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.core.session.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Home Assistant notification with a Remote Compose card, on a real platform player (API 36+):
 * the card captures with a button for its entity, the platform's RemoteViews player inflates and
 * draws it, and the notification posts with the card as its expanded body.
 */
@RunWith(AndroidJUnit4::class)
class NotificationCardTest {
  @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val snapshot = DemoData.snapshot()
  private val light = snapshot.states.keys.first { it.startsWith("light.") }

  @Test
  fun theCardBindsAButtonForItsEntity() {
    val captured = runBlocking { NotificationCards.captureDocument(context, light, snapshot) }
    val actions = NotificationCards.boundActions(captured).values
    assertTrue(
      "expected a toggle or service call for $light, got $actions",
      actions.any {
        (it is HaAction.Toggle && it.entityId == light) ||
          (it is HaAction.CallService && it.entityId == light)
      },
    )
  }

  @Test
  fun thePlatformPlayerDrawsTheCard() {
    val views = runBlocking { NotificationCards.capture(context, BASE_URL, light, snapshot) }
    lateinit var bitmap: Bitmap
    activity.scenario.onActivity { host ->
      val parent = FrameLayout(host)
      host.setContentView(parent)
      val view = views.apply(host, parent)
      parent.addView(view)
      val w = host.resources.displayMetrics.widthPixels
      val h = (256 * host.resources.displayMetrics.density).toInt()
      view.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST),
      )
      view.layout(0, 0, view.measuredWidth, view.measuredHeight)
      bitmap =
        Bitmap.createBitmap(
          view.measuredWidth,
          view.measuredHeight.coerceAtLeast(1),
          Bitmap.Config.ARGB_8888,
        )
      view.draw(Canvas(bitmap))
    }
    val colours = buildSet {
      for (x in 0 until bitmap.width step 8) for (y in 0 until bitmap.height step 8) add(
        bitmap.getPixel(x, y)
      )
    }
    assertTrue("the card drew nothing (${colours.size} colours)", colours.size > 2)
  }

  @Test
  fun theNotificationPostsWithTheCardAndBridgesToTheWatch() {
    instrumentation.uiAutomation.grantRuntimePermission(
      context.packageName,
      Manifest.permission.POST_NOTIFICATIONS,
    )
    val message =
      HaPushNotification(
        message = "Kitchen light left on",
        title = "Home",
        tag = "kitchen",
        entityId = light,
      )
    val views = runBlocking { NotificationCards.capture(context, BASE_URL, light, snapshot) }
    HaNotifications.handle(context, BASE_URL, message, watchHasApp = true, card = views)

    val nm = context.getSystemService(NotificationManager::class.java)
    val posted = nm.activeNotifications.firstOrNull { it.id == HaNotifications.idOf(message) }
    assertNotNull("the notification wasn't posted", posted)
    val notification = posted!!.notification
    assertNotNull("no card body", notification.bigContentView)
    val wear = NotificationCompat.WearableExtender(notification)
    assertEquals(HaNotifications.BRIDGE_TAG, wear.bridgeTag)
    assertEquals(message.dedupeKey, wear.dismissalId)
    assertEquals(listOf("Open on watch"), wear.actions.map { it.title.toString() })

    // HA's clear_notification with the same tag removes it.
    HaNotifications.handle(
      context,
      BASE_URL,
      HaPushNotification(message = HaPushNotification.CLEAR_MESSAGE, tag = "kitchen"),
      watchHasApp = true,
    )
    assertTrue(nm.activeNotifications.none { it.id == HaNotifications.idOf(message) })
  }

  private companion object {
    const val BASE_URL = "http://homeassistant.test:8123"
  }
}
