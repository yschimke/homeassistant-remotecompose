package ee.schimke.terrazzo.notify

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import ee.schimke.ha.client.HaPushNotification
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.wearsync.MobileWearSyncManager
import ee.schimke.terrazzo.wearsync.WearableApiGuard
import ee.schimke.terrazzo.wearsync.proto.WearNotification
import ee.schimke.terrazzo.wearsync.proto.WearNotificationAction
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Hands a Home Assistant notification to the Wear app, which posts it as its own local
 * notification, rather than bridging the phone's: a DataItem at [WearSyncPaths.notificationPath] is
 * the tickle (delivered when the watch next connects, if it isn't now). The watch's copy is native
 * — the entity's name and state, a toggle, HA's buttons — and its buttons run on the phone
 * ([PhoneWearNotificationService]).
 *
 * Deleting the item dismisses on both sides: the phone deletes it when its notification is
 * dismissed or cleared, the watch when its own is ([PhoneWearNotificationService] cancels the
 * phone's then).
 */
@OptIn(ExperimentalSerializationApi::class)
object WearNotifications {
  private const val TAG = "WearNotifications"
  /** Domains the watch shows a toggle for. */
  private val TOGGLES = setOf("light", "switch", "fan", "input_boolean", "automation")

  fun payload(
    id: Int,
    baseUrl: String,
    notification: HaPushNotification,
    entity: EntityState?,
    update: Boolean = false,
    nowMs: Long = System.currentTimeMillis(),
  ): WearNotification =
    WearNotification(
      id = id,
      baseUrl = baseUrl,
      title = notification.title ?: "Home Assistant",
      message = notification.message,
      tag = notification.tag.orEmpty(),
      entityName =
        entity
          ?.let { (it.attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: it.entityId }
          .orEmpty(),
      entityState = entity?.let { formatState(it) }.orEmpty(),
      toggleEntityId = entity?.entityId?.takeIf { it.substringBefore('.') in TOGGLES }.orEmpty(),
      actions = notification.actions.map { WearNotificationAction(it.action, it.title) },
      postedAtMs = nowMs,
      update = update,
    )

  /** Writes [payload] for the watch to post. Fire and forget: the Data Layer delivers it later. */
  fun post(context: Context, payload: WearNotification) {
    if (!WearableApiGuard.isUsable(context)) return
    val request =
      PutDataMapRequest.create(WearSyncPaths.notificationPath(payload.id))
        .apply {
          dataMap.putByteArray(MobileWearSyncManager.KEY_PROTO, ProtoBuf.encodeToByteArray(payload))
          dataMap.putLong(MobileWearSyncManager.KEY_TS, payload.postedAtMs)
        }
        .asPutDataRequest()
        .setUrgent()
    Wearable.getDataClient(context).putDataItem(request).addOnFailureListener {
      Log.w(TAG, "Couldn't hand notification ${payload.id} to the watch", it)
    }
  }

  /** Removes notification [id] from the watch (and stops it coming back). */
  fun remove(context: Context, id: Int) {
    if (!WearableApiGuard.isUsable(context)) return
    val uri =
      Uri.Builder().scheme("wear").authority("*").path(WearSyncPaths.notificationPath(id)).build()
    Wearable.getDataClient(context).deleteDataItems(uri).addOnFailureListener {
      Log.w(TAG, "Couldn't remove notification $id from the watch", it)
    }
  }
}
