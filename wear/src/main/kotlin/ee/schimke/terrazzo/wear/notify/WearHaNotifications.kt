package ee.schimke.terrazzo.wear.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ee.schimke.ha.rc.components.R as ComponentsR
import ee.schimke.terrazzo.wear.WearMainActivity
import ee.schimke.terrazzo.wearsync.proto.WearNotification

/**
 * The watch's own copy of a Home Assistant notification, posted from the phone's [WearNotification]
 * (the phone's copy is local-only, so nothing is bridged). Native fields only: the title, the full
 * message, the entity's name and state as its sub text, a toggle for the entity, and HA's buttons.
 * Every button and the dismissal go back to the phone ([WearNotificationActionReceiver]), which
 * acts with its login and dismisses both copies.
 */
object WearHaNotifications {
  const val CHANNEL_ID = "ha_notifications"
  /** The notification tag; ids are the phone's, per message. */
  internal const val NOTIFICATION_TAG = "ha"
  private const val MAX_ACTIONS = 3

  fun post(context: Context, notification: WearNotification) {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return
    createChannel(context)
    try {
      nm.notify(NOTIFICATION_TAG, notification.id, build(context, notification))
    } catch (denied: SecurityException) {
      // Permission revoked between the check and the post.
    }
  }

  fun cancel(context: Context, id: Int) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, id)
  }

  internal fun build(context: Context, n: WearNotification): android.app.Notification {
    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(ComponentsR.drawable.ic_launcher_monochrome)
        .setContentTitle(n.title)
        .setContentText(n.message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(n.message))
        .setSubText(subText(n))
        .setWhen(n.postedAtMs)
        .setShowWhen(n.postedAtMs > 0)
        .setAutoCancel(true)
        // A redraw for a new state replaces it without alerting again.
        .setOnlyAlertOnce(n.update)
        .setContentIntent(open(context, n.id))
        .setDeleteIntent(WearNotificationActionReceiver.dismissed(context, n))
    if (n.toggleEntityId.isNotEmpty()) {
      builder.addAction(0, toggleLabel(n), WearNotificationActionReceiver.toggle(context, n))
    }
    n.actions.take(MAX_ACTIONS).forEachIndexed { index, action ->
      builder.addAction(
        0,
        action.title,
        WearNotificationActionReceiver.action(context, n, index, action.action),
      )
    }
    return builder.build()
  }

  /** "Kitchen light · On", or whichever half is known. */
  internal fun subText(n: WearNotification): String? =
    listOf(n.entityName, n.entityState)
      .filter { it.isNotEmpty() }
      .joinToString(" · ")
      .ifEmpty { null }

  internal fun toggleLabel(n: WearNotification): String =
    when (n.entityState.lowercase()) {
      "on" -> "Turn off"
      "off" -> "Turn on"
      else -> "Toggle"
    }

  private fun createChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Home Assistant", NotificationManager.IMPORTANCE_DEFAULT)
        .apply { description = "Messages Home Assistant sends to your phone." }
    )
  }

  private fun open(context: Context, id: Int): PendingIntent =
    PendingIntent.getActivity(
      context,
      id,
      Intent(context, WearMainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .setData(Uri.parse("terrazzo-wear://notification/$id")),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
