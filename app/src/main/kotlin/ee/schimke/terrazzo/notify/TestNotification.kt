package ee.schimke.terrazzo.notify

import android.content.Context
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.terrazzo.TerrazzoApplication
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A test notification from Settings, through the same path as Home Assistant's own: tagged, with an
 * action button, bridged to the watch, and with a live card for one of the user's lights (its
 * button works). The public demo server is read-only and shared by everyone, so it can't send one;
 * this shows what a `notify.mobile_app_<device>` message looks like without a server sending one.
 */
object TestNotification {
  const val TAG = "terrazzo_test"
  const val ACK_ACTION = "TERRAZZO_TEST_ACK"

  fun message(entityId: String?) =
    HaPushNotification(
      message = "This is how Home Assistant's notify messages to this device look.",
      title = "Terrazzo test notification",
      tag = TAG,
      entityId = entityId,
      actions = listOf(HaPushNotification.Action(ACK_ACTION, "Got it")),
    )

  /**
   * Posts the test notification for [session]'s server; false when notifications aren't allowed.
   */
  suspend fun send(context: Context, session: HaSession): Boolean {
    if (!HaNotifications.allowed(context)) return false
    val snapshot = session.liveStates.value ?: runCatching { session.loadSnapshot() }.getOrNull()
    val entityId = snapshot?.states?.keys?.sorted()?.firstOrNull { it.startsWith("light.") }
    val message = message(entityId)
    val card = entityId?.let { NotificationCards.render(context, session, it) }
    HaNotifications.handle(
      context,
      session.baseUrl,
      message,
      watchHasApp(context),
      card,
      entity = entityId?.let { snapshot.states[it] },
    )
    // A live server's state changes redraw it; the offline demo's toggles redraw it themselves.
    if (card != null && !DemoData.isDemo(session.baseUrl)) {
      HaPushReceiver.track(message, snapshot.states[entityId])
    }
    return true
  }

  internal suspend fun watchHasApp(context: Context): Boolean {
    val app = context.applicationContext as? TerrazzoApplication ?: return false
    return withTimeoutOrNull(2_000) {
      app.wearAppInstallHelper.watches.first().any { it.appInstalled }
    } ?: false
  }
}
