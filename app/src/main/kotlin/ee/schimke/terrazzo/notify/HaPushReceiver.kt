package ee.schimke.terrazzo.notify

import android.content.Context
import android.util.Log
import ee.schimke.terrazzo.TerrazzoApplication
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Receives Home Assistant's `notify.mobile_app_<device>` messages on the app's live session and
 * posts them ([HaNotifications]). HA delivers them over the websocket's push channel, so they
 * arrive while the app holds its connection (in the foreground); HA's FCM relay only serves the
 * official companion apps.
 */
object HaPushReceiver {
  private const val TAG = "HaPushReceiver"

  /** Runs for [session]'s lifetime; returns at once for the demo or an unregistered server. */
  suspend fun run(context: Context, graph: TerrazzoGraph, session: HaSession) {
    if (DemoData.isDemo(session.baseUrl)) return
    val webhookId = webhookId(graph, session) ?: return
    val app = context.applicationContext as TerrazzoApplication
    coroutineScope {
      val watchHasApp =
        app.wearAppInstallHelper.watches
          .map { watches -> watches.any { it.appInstalled } }
          .stateIn(this, SharingStarted.Eagerly, false)
      session.pushNotifications(webhookId).collect { notification ->
        val card = notification.entityId?.let { NotificationCards.render(context, session, it) }
        HaNotifications.handle(context, session.baseUrl, notification, watchHasApp.value, card)
      }
    }
  }

  /**
   * This install's `mobile_app` webhook for [session]'s server. A registration made before the push
   * channel existed is made again (the same device id), so HA has somewhere to send.
   */
  private suspend fun webhookId(graph: TerrazzoGraph, session: HaSession): String? {
    val stored = graph.mobileAppStore.get(session.baseUrl)
    if (stored?.pushChannel == true) return stored.webhookId
    val token = session.accessToken ?: return stored?.webhookId
    return runCatching { graph.mobileAppRegistrar.register(session.baseUrl, token).webhookId }
      .onFailure { Log.w(TAG, "mobile_app registration failed", it) }
      .getOrElse { stored?.webhookId }
  }
}
