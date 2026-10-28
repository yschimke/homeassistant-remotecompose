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
    // A locked card's control: its action is in the metadata only (None while locked). Without
    // metadata the static payload would run it whatever the lock says, so it fails closed.
    val requiresMetadata = intent.getBooleanExtra(EXTRA_REQUIRES_METADATA, false)
    if (requiresMetadata && !metadataWasForwarded) {
      Log.w(TAG, "Refusing a locked control on widget $widgetId: the platform sent no metadata")
      return
    }
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
        // A slider's value exists only in the tap's metadata, which the confirmation activity
        // can't receive (its PendingIntent is immutable), and a locked card's control comes here
        // rather than to that activity too: a high-risk one runs only while the
        // app's controls are unlocked.
        if (
          (action is HaAction.SetValue || requiresMetadata) &&
            !WidgetActionRunner.runsWithoutAsking(context.applicationContext, widgetId, call)
        ) {
          Log.w(TAG, "Refusing a high-risk control on widget $widgetId while controls are locked")
          context
            .terrazzoGraph()
            .logStore
            .recordLocalAction(
              "Widget control refused: unlock controls in the app",
              actionEntityId(call),
            )
          return@launch
        }
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
        is HaAction.SetValue ->
          "Widget set ${action.domain}.${action.service} ${action.field}" to action.entityId
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

    /**
     * Set on a locked card's control (`WidgetActionRegistry.lockedEntries`): the tap runs only with
     * the RemoteCompose metadata, which says whether the card was unlocked.
     */
    const val EXTRA_REQUIRES_METADATA = "requires_metadata"

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
  // A slider's position is where the finger left it: the app's own is the same slider resting where
  // it was drawn, and only the position may differ, within the slider's range.
  if (action is HaAction.SetValue && own is HaAction.SetValue)
    return action.inRange && own.copy(ticks = action.ticks) == action
  return own == action
}

/** Decode a delivered action, rejecting capture-time PIN placeholders that contain no digits. */
internal fun decodeDeliveredWidgetAction(
  remoteComposeMetadata: String?,
  fallbackPayload: String?,
  resolvedAction: String? = null,
): HaAction? {
  // A locked card's control sends `None` until it is unlocked: that holds whatever the app's own
  // payload for the tap is, a design template's resolved action included.
  val delivered = decodeHaAction(decodeWidgetActionMetadata(remoteComposeMetadata)?.actionPayload)
  if (delivered == HaAction.None) return HaAction.None
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
