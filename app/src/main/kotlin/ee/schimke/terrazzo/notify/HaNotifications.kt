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
import ee.schimke.ha.model.EntityState
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
 * - Without the Wear app, it is bridged to a paired watch with [BRIDGE_TAG] and that key as its
 *   dismissal id, so dismissing it on either device dismisses both.
 * - A message with an entity carries its live card ([NotificationCards]) as the phone's expanded
 *   body; [HaPushReceiver] redraws it as the entity changes ([refresh]), for as long as the
 *   notification shows. The watch never gets the RemoteViews: it shows the standard fields (title,
 *   the full message as BigTextStyle, actions), which every notification keeps, card or not.
 * - With the Wear app on a paired watch, the phone's copy is local-only and the Wear app posts its
 *   own, richer one from a Data Layer item ([WearNotifications]); dismissing either dismisses both.
 *   Without it, the notification is bridged as above.
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

  /** Whether [notification] is still showing (not dismissed, tapped away or cleared). */
  fun isShowing(context: Context, notification: HaPushNotification): Boolean {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    val id = idOf(notification)
    return nm.activeNotifications.any { it.tag == NOTIFICATION_TAG && it.id == id }
  }

  /**
   * Posts [notification], or removes the one it clears. With [watchHasApp] the phone's copy is
   * local-only and the Wear app posts its own ([WearNotifications]); otherwise it is bridged.
   * [card] is a Remote Compose card for the notification's [entity], when one could be drawn; a
   * [refresh] redraws it for [entity]'s new state, without alerting, on the phone and the watch.
   */
  fun handle(
    context: Context,
    baseUrl: String,
    notification: HaPushNotification,
    watchHasApp: Boolean,
    card: RemoteViews? = null,
    refresh: Boolean = false,
    entity: EntityState? = null,
  ) {
    val nm = NotificationManagerCompat.from(context)
    val id = idOf(notification)
    if (notification.isClear) {
      if (notification.tag != null) {
        nm.cancel(NOTIFICATION_TAG, id)
        WearNotifications.remove(context, id)
      }
      return
    }
    if (!allowed(context)) return
    // A state change without a card to redraw: only the watch's copy shows the state.
    if (refresh && card == null) {
      if (watchHasApp && entity != null) {
        WearNotifications.post(
          context,
          WearNotifications.payload(id, baseUrl, notification, entity, update = true),
        )
      }
      return
    }
    createChannel(context)
    try {
      nm.notify(
        NOTIFICATION_TAG,
        id,
        build(context, baseUrl, notification, watchHasApp, card, refresh),
      )
    } catch (denied: SecurityException) {
      // Permission revoked between the check and the post.
      return
    }
    // A refresh reaches the watch only with the entity's new state to show.
    if (watchHasApp && (!refresh || entity != null)) {
      WearNotifications.post(
        context,
        WearNotifications.payload(id, baseUrl, notification, entity, update = refresh),
      )
    }
  }

  /** Cancels the phone's notification [id] (the watch dismissed its copy). */
  fun cancel(context: Context, id: Int) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, id)
  }

  internal fun build(
    context: Context,
    baseUrl: String,
    notification: HaPushNotification,
    watchHasApp: Boolean,
    card: RemoteViews? = null,
    refresh: Boolean = false,
  ): android.app.Notification {
    val id = idOf(notification)
    // Bridged (no Wear app): one dismissal id on both devices, and a tag the Wear app could
    // suppress.
    val wear =
      NotificationCompat.WearableExtender()
        .setBridgeTag(BRIDGE_TAG)
        .setDismissalId(notification.dedupeKey)
    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(ComponentsR.drawable.ic_launcher_monochrome)
        .setContentTitle(notification.title ?: "Home Assistant")
        .setContentText(notification.message)
        .setAutoCancel(true)
        // A card redrawn for a new state replaces the notification without alerting again.
        .setOnlyAlertOnce(refresh)
        .setContentIntent(contentIntent(context, id, notification))
        .extend(wear)
    if (watchHasApp) {
      // The Wear app posts its own copy; dismissing this one dismisses that too.
      builder
        .setLocalOnly(true)
        .setDeleteIntent(HaNotificationActionReceiver.dismissed(context, id))
    }
    notification.group?.let { builder.setGroup(it) }
    notification.actions.take(MAX_ACTIONS).forEachIndexed { index, action ->
      builder.addAction(
        0,
        action.title,
        HaNotificationActionReceiver.action(context, id, index, baseUrl, notification, action),
      )
    }
    if (card != null) {
      // The card is the phone's expanded body only. No `setStyle(...)`: on Android 17 DP, pairing
      // BigTextStyle (or DecoratedCustomViewStyle) with a DrawInstructions-bearing custom view
      // routes inflation through `Notification$Builder.minimallyDecoratedExpandedContentView` →
      // `buildCustomContentIntoTemplate` → `RemoteViews.addView`, where the new
      // `validateNestedViews` refuses with "Cannot add a nested view that has DrawInstructions."
      // and SystemUI drops the notification with REASON_ERROR before the user sees it. Omitting
      // the style keeps the standard notification chrome around the custom view — same visual
      // decoration the earlier BigTextStyle comment described — and sidesteps the template-wrap
      // path entirely. The watch, which never gets RemoteViews, keeps title + contentText; a card
      // notification's text is short by nature, so losing the BigText expansion on the bridged
      // watch copy is acceptable.
      builder.setCustomBigContentView(card)
    } else {
      builder.setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
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
