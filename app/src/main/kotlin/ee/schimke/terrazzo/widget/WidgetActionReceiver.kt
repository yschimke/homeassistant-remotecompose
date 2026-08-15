package ee.schimke.terrazzo.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.decodeWidgetActionMetadata
import ee.schimke.ha.rc.decodeHaAction
import ee.schimke.terrazzo.terrazzoGraph
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Endpoint for numeric actions fired by the launcher's RemoteCompose `RemoteViews` player.
 *
 * This prototype deliberately stops at a decoded, observable app event. Calling Home Assistant from
 * here needs a background session plus an explicit write-policy decision; opening UI from a
 * receiver is also subject to Android's background-activity launch restrictions.
 */
class WidgetActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_WIDGET_ACTION) return

    val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, APP_WIDGET_ID_MISSING)
    val actionId = intent.getIntExtra(EXTRA_ACTION_ID, 0)
    val fallbackPayload = intent.getStringExtra(EXTRA_ACTION_PAYLOAD)
    val metadataWasForwarded = intent.hasExtra(EXTRA_REMOTE_COMPOSE_METADATA)
    val remoteComposeMetadata = intent.getStringExtra(EXTRA_REMOTE_COMPOSE_METADATA)
    val decodedMetadata = decodeWidgetActionMetadata(remoteComposeMetadata)
    val receivedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    Log.i(
      TAG,
      "Received widgetId=$widgetId actionId=$actionId " +
        "metadataForwarded=$metadataWasForwarded " +
        "actionMatchesFallback=${decodedMetadata?.actionPayload == fallbackPayload} " +
        "documentEntityId=${decodedMetadata?.entityId} " +
        "documentValue=${decodedMetadata?.currentValue} " +
        "documentTime=${decodedMetadata?.documentTime} " +
        "receivedAt=$receivedAt",
    )
    Log.d(TAG, "remotecompose_metadata=$remoteComposeMetadata fallbackPayload=$fallbackPayload")

    // Prefer the value resolved by RemoteCompose at interaction time. The mirrored payload keeps
    // actions working on platform versions whose RemoteViews bridge drops the metadata callback.
    val payload = decodedMetadata?.actionPayload ?: fallbackPayload
    val action = decodeHaAction(payload)
    if (action == null) {
      Log.w(TAG, "Widget action had no decodable payload; metadataForwarded=$metadataWasForwarded")
      return
    }

    Log.i(TAG, "Decoded widget action=$action")
    recordAction(context, action)
  }

  private fun recordAction(context: Context, action: HaAction) {
    val (summary, entityId) =
      when (action) {
        is HaAction.Toggle -> "Widget toggle" to action.entityId
        is HaAction.CallService ->
          "Widget call ${action.domain}.${action.service}" to action.entityId
        is HaAction.MoreInfo -> "Widget more info" to action.entityId
        is HaAction.Navigate -> "Widget navigate ${action.path}" to null
        is HaAction.Url -> "Widget URL ${action.url}" to null
        is HaAction.AlarmKey -> "Widget alarm key ${action.key}" to action.entityId
        is HaAction.AlarmPin -> "Widget alarm PIN ${action.pin}" to action.entityId
        is HaAction.AlarmIntent -> "Widget alarm ${action.service}" to action.entityId
        HaAction.None -> return
      }
    context.terrazzoGraph().logStore.recordLocalAction(summary, entityId)
  }

  companion object {
    const val ACTION_WIDGET_ACTION = "ee.schimke.terrazzo.WIDGET_ACTION"
    const val EXTRA_WIDGET_ID = "widget_id"
    const val EXTRA_ACTION_ID = "action_id"
    const val EXTRA_ACTION_PAYLOAD = "action_payload"

    /** Hard-coded by the platform RemoteViews RemoteCompose bridge; no public constant exists. */
    const val EXTRA_REMOTE_COMPOSE_METADATA = "remotecompose_metadata"

    private const val APP_WIDGET_ID_MISSING = -1
    private const val TAG = "WidgetActionReceiver"
  }
}
