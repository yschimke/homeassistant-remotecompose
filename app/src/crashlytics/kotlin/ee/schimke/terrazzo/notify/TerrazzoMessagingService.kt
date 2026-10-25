package ee.schimke.terrazzo.notify

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home Assistant notifications from the Terrazzo push relay: HA sent them to `push_url` because the
 * app held no websocket (or didn't confirm one in time). Each is a data message, posted here as the
 * websocket's would be ([HaNotifications]), the watch's copy included; a message delivered both
 * ways posts once, under one id. Without a live session it carries no card or entity state.
 */
class TerrazzoMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    val notification = PushRelay.fromRelay(message.data) ?: return
    val webhookId = message.data["webhook_id"] ?: return
    // Called on a background thread, with about 20 seconds to finish.
    runBlocking {
      withTimeoutOrNull(HANDLE_TIMEOUT_MS) {
        val graph = applicationContext.terrazzoGraph()
        val baseUrl = graph.mobileAppStore.instanceForWebhook(webhookId) ?: return@withTimeoutOrNull
        HaNotifications.handle(
          applicationContext,
          baseUrl,
          notification,
          TestNotification.watchHasApp(applicationContext),
        )
      } ?: Log.w(TAG, "Relayed notification not posted")
    }
  }

  /** FCM rotated the token: every registered server needs the new one. */
  override fun onNewToken(token: String) {
    runBlocking {
      withTimeoutOrNull(HANDLE_TIMEOUT_MS) {
        PushRelay.syncAll(applicationContext.terrazzoGraph(), token)
      }
    }
  }

  private companion object {
    const val TAG = "TerrazzoMessaging"
    const val HANDLE_TIMEOUT_MS = 15_000L
  }
}
