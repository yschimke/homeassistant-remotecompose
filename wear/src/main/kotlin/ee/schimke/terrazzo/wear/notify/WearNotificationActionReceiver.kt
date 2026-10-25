package ee.schimke.terrazzo.wear.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.Wearable
import ee.schimke.terrazzo.wearsync.proto.WearNotification
import ee.schimke.terrazzo.wearsync.proto.WearNotificationTap
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * A watch notification's buttons, each a [WearNotificationTap] to the phone
 * ([WearSyncPaths.NOTIFICATION_TAP_MESSAGE]): a toggle names the entity, an HA button its action.
 * The phone runs it and, for an HA button, takes both copies down (this one at once). A dismissal
 * deletes the notification's Data Layer item instead, which the phone sees even after being away.
 */
class WearNotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_TAP) return
    val tap = intent.getByteArrayExtra(EXTRA_TAP)?.let { decodeProto<WearNotificationTap>(it) }
    if (tap == null) return
    if (tap.action.isNotEmpty()) WearHaNotifications.cancel(context, tap.id)
    if (tap.action.isEmpty() && tap.toggleEntityId.isEmpty()) {
      // Dismissed: deleting the phone's item reaches it whenever it next connects, and its
      // listener cancels the phone's copy. A message would be lost while it is away.
      val uri =
        Uri.Builder()
          .scheme("wear")
          .authority("*")
          .path(WearSyncPaths.notificationPath(tap.id))
          .build()
      Wearable.getDataClient(context).deleteDataItems(uri).addOnFailureListener {
        Log.w(TAG, "Couldn't remove dismissed notification ${tap.id}", it)
      }
      return
    }
    val pending = goAsync()
    scope.launch {
      try {
        withTimeout(SEND_TIMEOUT_MS) { send(context, tap) }
      } catch (failure: Exception) {
        Log.w(TAG, "Couldn't send notification tap to the phone", failure)
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun send(context: Context, tap: WearNotificationTap) {
    val nodes = Wearable.getNodeClient(context).connectedNodes.await()
    val bytes = encodeProto(tap)
    val messages = Wearable.getMessageClient(context)
    for (node in nodes) {
      messages.sendMessage(node.id, WearSyncPaths.NOTIFICATION_TAP_MESSAGE, bytes).await()
    }
  }

  companion object {
    private const val TAG = "WearNotificationAction"
    private const val ACTION_TAP = "ee.schimke.terrazzo.wear.NOTIFICATION_TAP"
    private const val EXTRA_TAP = "tap"
    private const val SEND_TIMEOUT_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun toggle(context: Context, n: WearNotification): PendingIntent =
      intent(context, n, "toggle", n.tap(toggleEntityId = n.toggleEntityId))

    fun action(context: Context, n: WearNotification, index: Int, action: String): PendingIntent =
      intent(context, n, "action/$index", n.tap(action = action))

    fun dismissed(context: Context, n: WearNotification): PendingIntent =
      intent(context, n, "dismissed", n.tap())

    private fun WearNotification.tap(action: String = "", toggleEntityId: String = "") =
      WearNotificationTap(
        id = id,
        baseUrl = baseUrl,
        tag = tag,
        action = action,
        toggleEntityId = toggleEntityId,
      )

    private fun intent(
      context: Context,
      n: WearNotification,
      kind: String,
      tap: WearNotificationTap,
    ): PendingIntent =
      PendingIntent.getBroadcast(
        context,
        n.id,
        Intent(context, WearNotificationActionReceiver::class.java)
          .setAction(ACTION_TAP)
          // Distinct data per button: extras don't take part in PendingIntent identity.
          .setData(Uri.parse("terrazzo-wear://notification-tap/${n.id}/$kind"))
          .putExtra(EXTRA_TAP, encodeProto(tap)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
  }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
  kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
    addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
  }
