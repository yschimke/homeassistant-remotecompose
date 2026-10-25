package ee.schimke.terrazzo.wear.notify

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import ee.schimke.terrazzo.wear.sync.WearSyncRepository
import ee.schimke.terrazzo.wearsync.proto.WearNotification
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.decodeProto

/**
 * The phone's tickle for a Home Assistant notification, heard whether or not the app runs: a
 * changed `/wear/notification/<id>` item posts (or updates) the watch's own notification
 * ([WearHaNotifications]), a deleted one (dismissed or cleared on the phone, or signed out) cancels
 * it.
 */
class WearNotificationListenerService : WearableListenerService() {
  override fun onDataChanged(events: DataEventBuffer) {
    for (event in events) {
      val path = event.dataItem.uri.path ?: continue
      val id = WearSyncPaths.notificationId(path) ?: continue
      when (event.type) {
        DataEvent.TYPE_DELETED -> WearHaNotifications.cancel(applicationContext, id)
        DataEvent.TYPE_CHANGED -> {
          val bytes =
            DataMapItem.fromDataItem(event.dataItem)
              .dataMap
              .getByteArray(WearSyncRepository.KEY_PROTO) ?: continue
          decodeProto<WearNotification>(bytes)?.let {
            WearHaNotifications.post(applicationContext, it)
          }
        }
      }
    }
  }
}
