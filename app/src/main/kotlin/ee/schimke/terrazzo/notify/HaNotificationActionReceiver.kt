package ee.schimke.terrazzo.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.encodeHaAction
import ee.schimke.terrazzo.widget.WidgetActionReceiver
import ee.schimke.terrazzo.widget.WidgetActionRunner
import ee.schimke.terrazzo.widget.WidgetOpenRequests
import ee.schimke.terrazzo.widget.decodeDeliveredWidgetAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Home Assistant notification's buttons.
 * - An HA action (`data.actions[].action`) fires `mobile_app_notification_action` with the action
 *   and the message's tag, as the companion apps do, so automations waiting on it run. It goes on
 *   the app's socket while connected, else a short-lived session ([WidgetActionRunner.fireEvent]).
 * - "Open on watch" (watch-only, see [HaNotifications]) starts the Wear app on the paired watch.
 *
 * An action with a `uri` never reaches here: it is an activity `PendingIntent` that opens it.
 */
class HaNotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      ACTION_HA -> fireHaAction(context, intent)
      ACTION_OPEN_ON_WATCH -> openOnWatch(context)
      ACTION_CARD -> runCardAction(context, intent)
    }
  }

  /** A tap on the notification's Remote Compose card ([NotificationCards]). */
  private fun runCardAction(context: Context, intent: Intent) {
    val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: return
    // As for a widget: the action the player resolved at tap time, else the captured one.
    val action =
      decodeDeliveredWidgetAction(
        intent.getStringExtra(WidgetActionReceiver.EXTRA_REMOTE_COMPOSE_METADATA),
        intent.getStringExtra(EXTRA_PAYLOAD),
      ) ?: return
    val pending = goAsync()
    scope.launch {
      try {
        WidgetActionRunner.runFor(context, baseUrl, action)
      } catch (failure: Exception) {
        Log.w(TAG, "Notification card action failed", failure)
      } finally {
        pending.finish()
      }
    }
  }

  private fun fireHaAction(context: Context, intent: Intent) {
    val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: return
    val action = intent.getStringExtra(EXTRA_ACTION) ?: return
    val tag = intent.getStringExtra(EXTRA_TAG)
    NotificationManagerCompat.from(context)
      .cancel(NOTIFICATION_TAG, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    val data = buildJsonObject {
      put("action", action)
      tag?.let { put("tag", it) }
    }
    val pending = goAsync()
    scope.launch {
      try {
        WidgetActionRunner.fireEvent(context, baseUrl, EVENT_TYPE, data)
      } catch (failure: Exception) {
        Log.w(TAG, "Notification action $action failed", failure)
      } finally {
        pending.finish()
      }
    }
  }

  private fun openOnWatch(context: Context) {
    val open =
      Intent(Intent.ACTION_VIEW)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setData(Uri.parse(WEAR_OPEN_URI))
    runCatching { RemoteActivityHelper(context).startRemoteActivity(open, null) }
      .onFailure { Log.w(TAG, "Couldn't open the Wear app", it) }
  }

  companion object {
    private const val TAG = "HaNotificationAction"
    const val ACTION_HA = "ee.schimke.terrazzo.NOTIFICATION_ACTION"
    const val ACTION_OPEN_ON_WATCH = "ee.schimke.terrazzo.NOTIFICATION_OPEN_ON_WATCH"
    const val ACTION_CARD = "ee.schimke.terrazzo.NOTIFICATION_CARD_ACTION"
    private const val EXTRA_PAYLOAD = "payload"
    /** HA's event for a notification button, as the companion apps fire it. */
    const val EVENT_TYPE = "mobile_app_notification_action"
    /** The Wear app's entry point for "Open on watch" (an intent filter on WearMainActivity). */
    const val WEAR_OPEN_URI = "terrazzo-wear://open"
    private const val NOTIFICATION_TAG = "ha"
    private const val EXTRA_BASE_URL = "base_url"
    private const val EXTRA_ACTION = "action"
    private const val EXTRA_TAG = "tag"
    private const val EXTRA_NOTIFICATION_ID = "notification_id"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The button for [action] of [notification] (posted as [notificationId]). */
    fun action(
      context: Context,
      notificationId: Int,
      index: Int,
      baseUrl: String,
      notification: HaPushNotification,
      action: HaPushNotification.Action,
    ): PendingIntent {
      // Distinct data per button: extras don't take part in PendingIntent identity.
      val data = Uri.parse("terrazzo://notification-action/$notificationId/$index")
      val uri = action.uri
      if (uri != null) {
        val open =
          (if (uri.startsWith("/")) WidgetOpenRequests.launchIntent(context, HaAction.Navigate(uri))
          else null)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
          context,
          notificationId + index + 1,
          open.setData(open.data ?: data),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      }
      val intent =
        Intent(context, HaNotificationActionReceiver::class.java)
          .setAction(ACTION_HA)
          .setData(data)
          .putExtra(EXTRA_BASE_URL, baseUrl)
          .putExtra(EXTRA_ACTION, action.action)
          .putExtra(EXTRA_TAG, notification.tag)
          .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
      return PendingIntent.getBroadcast(
        context,
        notificationId + index + 1,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    }

    /**
     * The PendingIntent for host action [actionId] of a notification card: details, navigate and
     * URL taps open them; toggles and service calls run here. Null for a control a notification
     * can't serve (an alarm keypad).
     */
    fun cardAction(
      context: Context,
      baseUrl: String,
      entityId: String,
      actionId: Int,
      action: HaAction,
    ): PendingIntent? {
      val data = Uri.parse("terrazzo://notification-card/${Uri.encode(entityId)}/$actionId")
      val open =
        when (action) {
          is HaAction.Url -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
          is HaAction.MoreInfo,
          is HaAction.Navigate -> WidgetOpenRequests.launchIntent(context, action)
          else -> null
        }
      if (open != null) {
        return PendingIntent.getActivity(
          context,
          actionId,
          open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setData(open.data ?: data),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      }
      if (action !is HaAction.Toggle && action !is HaAction.CallService) return null
      val intent =
        Intent(context, HaNotificationActionReceiver::class.java)
          .setAction(ACTION_CARD)
          .setData(data)
          .putExtra(EXTRA_BASE_URL, baseUrl)
          .putExtra(EXTRA_PAYLOAD, encodeHaAction(action))
      // Mutable: the player adds the action's metadata (its value at tap time) to the intent.
      return PendingIntent.getBroadcast(
        context,
        actionId,
        intent,
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    }

    fun openOnWatch(context: Context, notificationId: Int): PendingIntent =
      PendingIntent.getBroadcast(
        context,
        notificationId,
        Intent(context, HaNotificationActionReceiver::class.java)
          .setAction(ACTION_OPEN_ON_WATCH)
          .setData(Uri.parse("terrazzo://notification-watch/$notificationId")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
  }
}
