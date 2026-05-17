package ee.schimke.ha.model

import kotlinx.serialization.Serializable

/**
 * A Home Assistant *persistent notification* — the things that pile up behind the bell icon in HA's
 * frontend. Created by automations (`service: persistent_notification.create`), integration
 * discovery banners, or anything calling `notify.persistent_notification`.
 *
 * Not to be confused with mobile-app push notifications: those don't come over the WebSocket at all
 * (FCM / APNS instead). This is what the HA frontend shows in the bell drawer, and it's exactly
 * what the `persistent_notifications_updated` event flags as changed.
 */
@Serializable
data class HaNotification(
  val notificationId: String,
  val title: String?,
  val message: String,
  val createdAt: String? = null,
)
