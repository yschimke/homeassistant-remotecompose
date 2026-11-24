package ee.schimke.terrazzo.notify

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.widget.WidgetStateWorker
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Silent wakes from the Terrazzo push relay ([PushRelay]): the Terrazzo integration holds a Home
 * Assistant notification for this device and asked for the app. The message carries no content,
 * only `type: sync` and the registration's `webhook_id`; the app connects with its own login and
 * takes what was held. A `type: state` wake says an entity a widget reads changed
 * ([WidgetStateWorker]).
 */
class TerrazzoMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    // An entity a widget reads visibly changed: redraw them, following it while it moves.
    if (message.data["type"] == "state") {
      message.data["webhook_id"]?.let { WidgetStateWorker.enqueue(applicationContext, it) }
      return
    }
    if (message.data["type"] != "sync") return
    // Called on a background thread, with about 20 seconds to finish.
    runBlocking {
      val completed =
        withTimeoutOrNull(FCM_WAKE_TIMEOUT) {
          try {
            PushRelay.wake(
              applicationContext,
              applicationContext.terrazzoGraph(),
              message.data["webhook_id"],
              null,
            )
            true
          } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
          } catch (t: Throwable) {
            Log.w(TAG, "Silent push sync failed", t)
            true
          }
        }
      if (completed == null) {
        Log.w(TAG, "Silent push sync timed out after $FCM_WAKE_TIMEOUT")
      }
    }
  }

  /** FCM rotated the token: each server's integration needs the new one to wake the app. */
  override fun onNewToken(token: String) {
    runBlocking {
      withTimeoutOrNull(FCM_WAKE_TIMEOUT) {
        try {
          PushRelay.wake(applicationContext, applicationContext.terrazzoGraph(), null, token)
        } catch (ce: kotlinx.coroutines.CancellationException) {
          throw ce
        } catch (t: Throwable) {
          Log.w(TAG, "FCM token registration sync failed", t)
        }
      }
    }
  }

  private companion object {
    const val TAG = "TerrazzoMessaging"
    val FCM_WAKE_TIMEOUT = 15.seconds
  }
}
