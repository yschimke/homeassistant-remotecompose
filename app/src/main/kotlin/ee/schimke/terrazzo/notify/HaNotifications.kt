package ee.schimke.terrazzo.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.R as ComponentsR
import ee.schimke.terrazzo.MainActivity
import ee.schimke.terrazzo.widget.WidgetOpenRequests

/**
 * Posts Home Assistant's `notify.mobile_app_<device>` messages as Android notifications, once the
 * user has allowed notifications (nothing is posted, or asked for, before that).
 *
 * One message is one notification everywhere it shows:
 * - Its id is [HaPushNotification.dedupeKey]: HA's `tag` when set, so a re-sent message replaces
 *   the first and `clear_notification` removes it; otherwise the content, so a reconnect that
 *   replays a message doesn't post it twice.
 * - It is bridged to a paired watch with [BRIDGE_TAG] and that key as its dismissal id, so
 *   dismissing it on either device dismisses both, and a Wear app that posts its own copy can
 *   suppress the bridged one by tag (`BridgingConfig`) instead of showing two.
 * - While the watch has the Wear app installed, it carries a watch-only "Open on watch" action.
 *   That stays harmless once the Wear app posts its own copy: the bridged notification, and its
 *   action, are suppressed there.
 */
object HaNotifications {
  const val CHANNEL_ID = "ha_notifications"
  /** The bridge tag every HA notification carries to the watch. */
  const val BRIDGE_TAG = "ha"
  /** The notification tag; ids are per message within it. */
  private const val NOTIFICATION_TAG = "ha"
  private const val MAX_ACTIONS = 3

  /** Whether the user allows notifications (on API 33+, the POST_NOTIFICATIONS grant). */
  fun allowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

  fun createChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Home Assistant", NotificationManager.IMPORTANCE_DEFAULT)
        .apply { description = "Messages Home Assistant sends to this device." }
    )
  }

  fun idOf(notification: HaPushNotification): Int = notification.dedupeKey.hashCode()

  /**
   * Posts [notification], or removes the one it clears. [watchHasApp] adds the watch-only "Open on
   * watch" action; [card] is a Remote Compose card for the notification's entity, when one could be
   * drawn.
   */
  fun handle(
    context: Context,
    baseUrl: String,
    notification: HaPushNotification,
    watchHasApp: Boolean,
    card: RemoteViews? = null,
  ) {
    val nm = NotificationManagerCompat.from(context)
    if (notification.isClear) {
      notification.tag?.let { nm.cancel(NOTIFICATION_TAG, idOf(notification)) }
      return
    }
    if (!allowed(context)) return
    createChannel(context)
    try {
      nm.notify(
        NOTIFICATION_TAG,
        idOf(notification),
        build(context, baseUrl, notification, watchHasApp, card),
      )
    } catch (denied: SecurityException) {
      // Permission revoked between the check and the post.
    }
  }

  internal fun build(
    context: Context,
    baseUrl: String,
    notification: HaPushNotification,
    watchHasApp: Boolean,
    card: RemoteViews? = null,
  ): android.app.Notification {
    val id = idOf(notification)
    val wear =
      NotificationCompat.WearableExtender()
        .setBridgeTag(BRIDGE_TAG)
        .setDismissalId(notification.dedupeKey)
    if (watchHasApp) {
      wear.addAction(
        NotificationCompat.Action.Builder(
            ComponentsR.drawable.ic_launcher_monochrome,
            "Open on watch",
            HaNotificationActionReceiver.openOnWatch(context, id),
          )
          .build()
      )
    }
    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(ComponentsR.drawable.ic_launcher_monochrome)
        .setContentTitle(notification.title ?: "Home Assistant")
        .setContentText(notification.message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
        .setAutoCancel(true)
        .setContentIntent(contentIntent(context, id, notification))
        .extend(wear)
    notification.group?.let { builder.setGroup(it) }
    notification.actions.take(MAX_ACTIONS).forEachIndexed { index, action ->
      builder.addAction(
        0,
        action.title,
        HaNotificationActionReceiver.action(context, id, index, baseUrl, notification, action),
      )
    }
    if (card != null) {
      // The card replaces the expanded body; the collapsed row keeps the title and message.
      builder.setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomBigContentView(card)
    }
    return builder.build()
  }

  /**
   * A tap opens what the message points at: `clickAction` / `url` (a dashboard path in the app, a
   * full URL in the browser), else its entity's details, else the app.
   */
  private fun contentIntent(context: Context, id: Int, n: HaPushNotification): PendingIntent {
    val url = n.url
    val intent =
      when {
        url != null && url.startsWith("/") ->
          WidgetOpenRequests.launchIntent(context, HaAction.Navigate(url))
        url != null -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
        n.entityId != null ->
          WidgetOpenRequests.launchIntent(context, HaAction.MoreInfo(n.entityId!!))
        else -> null
      } ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Distinct data per notification, so one message's intent doesn't replace another's.
    intent.data = intent.data ?: Uri.parse("terrazzo://notification/$id")
    return PendingIntent.getActivity(
      context,
      id,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }
}
