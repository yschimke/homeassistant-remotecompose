package ee.schimke.terrazzo.notify

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.runBlocking

/**
 * Silent wakes from the Terrazzo push relay ([PushRelay]): the Terrazzo integration holds a Home
 * Assistant notification for this device and asked for the app. The message carries no content,
 * only `type: sync` and the registration's `webhook_id`; the app connects with its own login and
 * takes what was held.
 */
class TerrazzoMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    if (message.data["type"] != "sync") return
    // Called on a background thread, with about 20 seconds to finish.
    runBlocking {
      runCatching {
        PushRelay.wake(
          applicationContext,
          applicationContext.terrazzoGraph(),
          message.data["webhook_id"],
          null,
        )
      }
        .onFailure { Log.w(TAG, "Silent push sync failed", it) }
    }
  }

  /** FCM rotated the token: each server's integration needs the new one to wake the app. */
  override fun onNewToken(token: String) {
    runBlocking {
      runCatching {
        PushRelay.wake(applicationContext, applicationContext.terrazzoGraph(), null, token)
      }
    }
  }

  private companion object {
    const val TAG = "TerrazzoMessaging"
  }
}
