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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Endpoint for numeric actions fired by the launcher's RemoteCompose `RemoteViews` player.
 *
 * Toggles and service calls run against Home Assistant from here ([WidgetActionRunner]), within the
 * broadcast's time budget; taps that need the app in front never reach this receiver (they are
 * activity `PendingIntent`s, see `TerrazzoWidgetProvider`). Every action is also recorded for the
 * debug log.
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
    // Prefer the value resolved by RemoteCompose at interaction time. The mirrored payload keeps
    // fixed actions working on platform versions whose RemoteViews bridge drops the metadata
    // callback. Dynamic PINs deliberately fail closed because their capture-time fallback cannot
    // contain the digits entered later in the player.
    val action =
      decodeDeliveredWidgetAction(
        remoteComposeMetadata,
        fallbackPayload,
        intent.getStringExtra(EXTRA_RESOLVED_ACTION),
      )
    if (action == null) {
      Log.w(
        TAG,
        "Widget action had no complete decodable payload; " +
          "metadataForwarded=$metadataWasForwarded",
      )
      return
    }

    Log.i(TAG, "Decoded widget actionType=${action.javaClass.simpleName}")
    recordAction(context, action)
    if (!WidgetActionRunner.runs(action)) return
    if (
      !trustedWidgetAction(action, fallbackPayload, intent.getStringExtra(EXTRA_RESOLVED_ACTION))
    ) {
      Log.w(TAG, "Refusing a widget action the app didn't put on widget $widgetId")
      return
    }
    // Paired here, in arrival order: the runs below may start in any order on their pool.
    val call = WidgetActionRunner.prepare(widgetId, action) ?: return
    val pending = goAsync()
    runnerScope.launch {
      try {
        WidgetActionRunner.run(context.applicationContext, widgetId, call)
      } finally {
        pending.finish()
      }
    }
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
        is HaAction.AlarmPin -> "Widget alarm PIN submitted" to action.entityId
        is HaAction.AlarmIntent -> "Widget alarm ${action.service}" to action.entityId
        HaAction.None -> return
      }
    context.terrazzoGraph().logStore.recordLocalAction(summary, entityId)
  }

  companion object {
    private val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val ACTION_WIDGET_ACTION = "ee.schimke.terrazzo.WIDGET_ACTION"
    const val EXTRA_WIDGET_ID = "widget_id"
    const val EXTRA_ACTION_ID = "action_id"
    const val EXTRA_ACTION_PAYLOAD = "action_payload"

    /**
     * The card-local action a design-template widget resolved for this tap. The document's own
     * payload names template slots, never real entities, so this one wins when present.
     */
    const val EXTRA_RESOLVED_ACTION = "resolved_action"

    /** Hard-coded by the platform RemoteViews RemoteCompose bridge; no public constant exists. */
    const val EXTRA_REMOTE_COMPOSE_METADATA = "remotecompose_metadata"

    private const val APP_WIDGET_ID_MISSING = -1
    private const val TAG = "WidgetActionReceiver"
  }
}

/**
 * Whether [action] may write to Home Assistant: it must be exactly the action the app itself put on
 * the widget's `PendingIntent` ([resolvedAction], else [fallbackPayload]). The launcher can fill in
 * the RemoteCompose metadata extra, but it can't replace the app's own extras, so a write never
 * runs from metadata alone.
 */
internal fun trustedWidgetAction(
  action: HaAction,
  fallbackPayload: String?,
  resolvedAction: String?,
): Boolean {
  val own = decodeHaAction(resolvedAction ?: fallbackPayload) ?: return false
  // A widget PIN's digits are typed in the player, after the app captured the payload: the app's
  // own is the same entity's PIN with none yet (decodeDeliveredWidgetAction fills them in).
  if (action is HaAction.AlarmPin && own is HaAction.AlarmPin && own.pin.isEmpty())
    return own.entityId == action.entityId
  return own == action
}

/** Decode a delivered action, rejecting capture-time PIN placeholders that contain no digits. */
internal fun decodeDeliveredWidgetAction(
  remoteComposeMetadata: String?,
  fallbackPayload: String?,
  resolvedAction: String? = null,
): HaAction? {
  resolvedAction?.let { resolved ->
    val action = decodeHaAction(resolved) ?: return null
    if (action !is HaAction.AlarmPin || action.pin.isNotEmpty()) return action
    // A design-template widget's PIN: the entity is the resolved one, the digits are what the
    // document accumulated (its own payload names a template slot, never the real entity).
    val typed =
      decodeHaAction(decodeWidgetActionMetadata(remoteComposeMetadata)?.actionPayload)
        as? HaAction.AlarmPin
    return typed?.pin?.takeIf { it.isNotEmpty() }?.let { action.copy(pin = it) }
  }
  val payload = decodeWidgetActionMetadata(remoteComposeMetadata)?.actionPayload ?: fallbackPayload
  val action = decodeHaAction(payload) ?: return null
  return action.takeUnless { it is HaAction.AlarmPin && it.pin.isEmpty() }
}
