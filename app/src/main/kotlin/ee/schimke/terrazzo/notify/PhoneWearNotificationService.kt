package ee.schimke.terrazzo.notify

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.wearsync.proto.WearNotificationTap
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.widget.WidgetActionRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * The watch's side of a Home Assistant notification it posted itself ([WearNotifications]), heard
 * even while the phone app isn't running:
 * - its Data Layer item deleted, or a [WearNotificationTap] with no action (dismissed on the
 *   watch): the phone's copy goes too;
 * - a button on it ([WearSyncPaths.NOTIFICATION_TAP_MESSAGE]): the entity's toggle, or HA's action
 *   (`mobile_app_notification_action`, which also dismisses it on both), run on the phone's login.
 *   The offline demo flips its own entity and fires nothing.
 */
@OptIn(ExperimentalSerializationApi::class)
class PhoneWearNotificationService : WearableListenerService() {
  override fun onDataChanged(events: DataEventBuffer) {
    events
      .filter { it.type == DataEvent.TYPE_DELETED }
      .mapNotNull { it.dataItem.uri.path?.let(WearSyncPaths::notificationId) }
      .forEach { HaNotifications.cancel(applicationContext, it) }
  }

  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WearSyncPaths.NOTIFICATION_TAP_MESSAGE) return
    val tap =
      runCatching { ProtoBuf.decodeFromByteArray<WearNotificationTap>(event.data) }.getOrNull()
        ?: return
    if (tap.baseUrl.isEmpty()) return
    val demo = DemoData.isDemo(tap.baseUrl)
    // Called on a binder thread; each call has its own timeout.
    runBlocking {
      runCatching {
        when {
          tap.toggleEntityId.isNotEmpty() && demo -> {
            // The offline demo has no Home Assistant: flip the demo entity and redraw.
            val current =
              DemoData.snapshot().states[tap.toggleEntityId]?.state ?: return@runCatching
            DemoData.actionRouter.applyToggle(tap.toggleEntityId, current)
            HaNotificationActionReceiver.redrawDemo(
              applicationContext,
              tap.baseUrl,
              tap.toggleEntityId,
            )
          }
          tap.toggleEntityId.isNotEmpty() ->
            // The new state comes back through the live redraw ([HaPushReceiver]).
            WidgetActionRunner.runFor(
              applicationContext,
              tap.baseUrl,
              HaAction.Toggle(tap.toggleEntityId),
            )
          else -> {
            // An HA button, or dismissed on the watch: gone from both.
            HaNotifications.cancel(applicationContext, tap.id)
            WearNotifications.remove(applicationContext, tap.id)
            if (tap.action.isEmpty() || demo) return@runCatching
            WidgetActionRunner.fireEvent(
              applicationContext,
              tap.baseUrl,
              HaNotificationActionReceiver.EVENT_TYPE,
              buildJsonObject {
                put("action", tap.action)
                if (tap.tag.isNotEmpty()) put("tag", tap.tag)
              },
            )
          }
        }
      }
        .onFailure { Log.w(TAG, "Watch notification tap failed", it) }
    }
  }

  private companion object {
    const val TAG = "PhoneWearNotification"
  }
}
