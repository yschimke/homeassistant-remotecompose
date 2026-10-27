package ee.schimke.terrazzo.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.terrazzo.core.session.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the Android 17 DP regression where a `BigTextStyle` (or any style) + a DrawInstructions
 * custom bigContentView makes SystemUI run
 * `Notification$Builder.minimallyDecoratedExpandedContentView` → `buildCustomContentIntoTemplate` →
 * `RemoteViews.addView`, which hits a new `validateNestedViews` and throws:
 * ```
 * java.lang.IllegalArgumentException: Cannot add a nested view that has DrawInstructions.
 * ```
 *
 * SystemUI then removes the notification with REASON_ERROR within ~70 ms and the user never sees
 * it. The AOSP emulator images (API 35/36) don't yet carry the strict validation, so a bigger
 * matrix is in git history — this test keeps the regression guard: the production shape used
 * through [HaNotifications.build] must NOT set a `Notification.Style` whenever a card is present,
 * on every API this test can run on. Reflection peeks at `EXTRA_TEMPLATE`, which is the string
 * class name of the applied style (`BigTextStyle` / `DecoratedCustomViewStyle` / …).
 */
@RunWith(AndroidJUnit4::class)
class LiveCardInflationTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext

  @Test
  fun build_withCard_setsNoStyle() {
    val card = dummyCard()
    val built =
      HaNotifications.build(
        context = context,
        baseUrl = BASE_URL,
        notification = messageWithEntity(),
        watchHasApp = false,
        card = card,
      )
    val template = built.extras.getString(Notification.EXTRA_TEMPLATE)
    assertNull(
      "A card notification must not carry a Notification.Style; template = $template is what " +
        "triggers Android 17 DP's `validateNestedViews` rejection on a DrawInstructions custom view",
      template,
    )
    assertNotNull("the card must still be attached as the big content view", built.bigContentView)
  }

  @Test
  fun build_withoutCard_keepsBigTextStyle() {
    val built =
      HaNotifications.build(
        context = context,
        baseUrl = BASE_URL,
        notification = messageWithoutEntity(),
        watchHasApp = false,
        card = null,
      )
    val template = built.extras.getString(Notification.EXTRA_TEMPLATE)
    assertEquals(
      "A text-only notification keeps BigTextStyle so the watch's bridged copy expands the full " +
        "message",
      "android.app.Notification\$BigTextStyle",
      template,
    )
  }

  @Test
  @SdkSuppress(minSdkVersion = 36)
  fun post_withCard_survivesSystemUiInflation() {
    instrumentation.uiAutomation.grantRuntimePermission(
      context.packageName,
      Manifest.permission.POST_NOTIFICATIONS,
    )
    val snapshot = DemoData.snapshot()
    val light = snapshot.states.keys.first { it.startsWith("light.") }
    val views = runBlocking { NotificationCards.capture(context, BASE_URL, light, snapshot) }
    val built =
      HaNotifications.build(
        context = context,
        baseUrl = BASE_URL,
        notification = messageWithEntity(entity = light),
        watchHasApp = false,
        card = views,
      )
    assertNotEquals(
      "a style set anywhere upstream will route inflation through the DP-rejected template-wrap",
      "android.app.Notification\$BigTextStyle",
      built.extras.getString(Notification.EXTRA_TEMPLATE),
    )
    HaNotifications.createChannel(context)
    val nm = context.getSystemService(NotificationManager::class.java)
    val msg = messageWithEntity(entity = light)
    val id = HaNotifications.idOf(msg)
    val tag = "ha"
    nm.notify(tag, id, built)
    // Give SystemUI a window to run its inflater; a REASON_ERROR cancel lands within ~70 ms on
    // affected builds, so 1 s is plenty.
    Thread.sleep(1_000)
    val active = nm.activeNotifications
    val still = active.any { it.id == id && it.tag == tag }
    Log.i(
      TAG,
      "post_withCard_survivesSystemUiInflation: still=$still active=${active.size} (API ${android.os.Build.VERSION.SDK_INT})",
    )
    for (sbn in active) Log.i(TAG, "  active: id=${sbn.id} tag=${sbn.tag} pkg=${sbn.packageName}")
    nm.cancel(tag, id)
    assertEquals(
      "the live-card notification was dropped by SystemUI — check logcat for " +
        "`NotifContentInflater` and the current Notification.Style used by HaNotifications.build",
      true,
      still,
    )
  }

  private fun dummyCard(): RemoteViews =
    RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
      setTextViewText(android.R.id.text1, "placeholder card")
    }

  private fun messageWithEntity(entity: String = "light.demo"): HaPushNotification =
    HaPushNotification(message = "card body", title = "Home", tag = "card-test", entityId = entity)

  private fun messageWithoutEntity(): HaPushNotification =
    HaPushNotification(message = "plain body", title = "Home", tag = "plain-test")

  private companion object {
    const val TAG = "LiveCardInflationTest"
    const val BASE_URL = "http://homeassistant.test:8123"
  }
}
