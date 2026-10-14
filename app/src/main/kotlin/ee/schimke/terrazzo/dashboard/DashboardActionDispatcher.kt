package ee.schimke.terrazzo.dashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import ee.schimke.ha.rc.AlarmKeypadCoordinator
import ee.schimke.ha.rc.CardDocumentCache
import ee.schimke.ha.rc.HaActionDispatcher
import ee.schimke.ha.rc.LocalCardDocumentCache
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.logs.LogStore
import ee.schimke.terrazzo.core.session.CoverCommand
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.demoCoverPositionPercent
import ee.schimke.terrazzo.feedback.FeedbackCue
import ee.schimke.terrazzo.feedback.InteractionFeedback
import ee.schimke.terrazzo.feedback.feedbackCueFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Build the [HaActionDispatcher] that backs `LocalHaActionDispatcher` for the dashboard surface.
 * The dispatcher fans actions out by type:
 *
 * - [HaAction.Url] → `Intent.ACTION_VIEW` (opens a browser)
 * - [HaAction.Toggle] / [HaAction.CallService] → live `HaSession` calls through `call_service`
 *   (`homeassistant.toggle` for the bare `Toggle` form); demo sessions route through
 *   [DemoHaSession.actionRouter] so taps are visible without a network round-trip.
 * - [HaAction.Navigate] / [HaAction.MoreInfo] → logged for now — these need in-app navigation that
 *   the dashboard doesn't expose yet.
 *
 * With [feedback], every action that changes something is also felt (and, if the user turned sounds
 * on, heard): a toggle-on / toggle-off for switches — [stateOf] tells which way a bare toggle flips
 * — a key tap for keypads, a confirm for an alarm, and a reject when read-only mode drops an action
 * or the service call fails.
 *
 * In demo mode the dispatcher also evicts the [CardDocumentCache] so cards baking state into bytes
 * (e.g. the garage shutter card's `closedFraction`) re-encode against the new snapshot on the next
 * dashboard refresh — without that, toggles fire but the visual doesn't update.
 */
@Composable
fun rememberDashboardActionDispatcher(
  session: HaSession,
  readOnly: Boolean = true,
  feedback: InteractionFeedback? = null,
  stateOf: (String) -> String? = { null },
): HaActionDispatcher {
  val context = LocalContext.current
  val cache = LocalCardDocumentCache.current
  val scope = rememberCoroutineScope()
  val logStore = LocalTerrazzoGraph.current.logStore
  // Read fresh each dispatch so toggling the chip takes effect without
  // recreating the dispatcher (and losing AlarmKeypadCoordinator state).
  val readOnlyState = rememberUpdatedState(readOnly)
  val stateOfState = rememberUpdatedState(stateOf)
  return remember(session, context, cache, scope, logStore, feedback) {
    val core =
      DashboardActionDispatcher(
        context.applicationContext,
        session,
        cache,
        scope,
        logStore,
        readOnly = { readOnlyState.value },
        onFailure = { feedback?.play(FeedbackCue.Reject) },
      )
    // The keypad coordinator buffers per-key host actions and
    // translates ARM intents into a single `call_service` with the
    // entered code attached. Other action variants pass through.
    val dispatcher = AlarmKeypadCoordinator(core, scope)
    if (feedback == null) dispatcher
    else
      HaActionDispatcher { action ->
        // Cue before dispatching: the feel belongs to the touch, not to the round trip.
        feedbackCueFor(action) { stateOfState.value(it) }
          ?.let { cue ->
            // Read-only mode drops every write; a keypad key only fills the local buffer.
            val dropped = readOnlyState.value && cue != FeedbackCue.Key
            feedback.play(if (dropped) FeedbackCue.Reject else cue)
          }
        dispatcher.dispatch(action)
      }
  }
}

private class DashboardActionDispatcher(
  private val context: Context,
  private val session: HaSession,
  private val cache: CardDocumentCache,
  private val scope: CoroutineScope,
  private val logStore: LogStore,
  private val readOnly: () -> Boolean,
  private val onFailure: () -> Unit = {},
) : HaActionDispatcher {
  override fun dispatch(action: HaAction) {
    // Log every dispatched action (before any network round-trip)
    // so the debug Logs view sees the tap even if the call fails.
    // Unwired variants are still recorded so the screen reflects
    // that the tap landed.
    recordToLog(action)
    when (action) {
      is HaAction.Url -> launchUrl(action.url)
      is HaAction.Toggle -> {
        if (readOnly()) {
          Log.i(TAG, "Read-only mode: dropping toggle for ${action.entityId}")
          return
        }
        handleToggle(action.entityId)
      }
      is HaAction.CallService -> {
        if (readOnly()) {
          Log.i(TAG, "Read-only mode: dropping ${action.domain}.${action.service}")
          return
        }
        handleCallService(action)
      }
      is HaAction.MoreInfo,
      is HaAction.Navigate,
      // AlarmKey / AlarmIntent should be intercepted upstream by
      // AlarmKeypadCoordinator; logging here means an alarm-panel
      // action escaped the coordinator (most likely a bug).
      is HaAction.AlarmKey,
      is HaAction.AlarmIntent,
      HaAction.None -> Log.i(TAG, "Action not yet wired: $action")
      is HaAction.AlarmPin ->
        Log.i(TAG, "Alarm PIN action escaped coordinator for ${action.entityId}")
    }
  }

  private fun recordToLog(action: HaAction) {
    val (summary, entityId) =
      when (action) {
        is HaAction.Url -> "Open URL: ${action.url}" to null
        is HaAction.Toggle -> "Toggle" to action.entityId
        is HaAction.CallService -> "Call ${action.domain}.${action.service}" to action.entityId
        is HaAction.MoreInfo -> "More info" to action.entityId
        is HaAction.Navigate -> "Navigate to ${action.path}" to null
        is HaAction.AlarmKey -> "Alarm key ${action.key}" to action.entityId
        is HaAction.AlarmPin -> "Alarm PIN submitted" to action.entityId
        is HaAction.AlarmIntent -> "Alarm ${action.service}" to action.entityId
        HaAction.None -> return
      }
    logStore.recordLocalAction(summary = summary, entityId = entityId)
  }

  private fun launchUrl(url: String) {
    val uri =
      runCatching { Uri.parse(url) }.getOrNull()
        ?: run {
          Log.w(TAG, "Refusing to launch unparseable URL: $url")
          return
        }
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No activity found to open $url", e)
    }
  }

  private fun handleToggle(entityId: String) {
    val demo = session as? DemoHaSession
    if (demo != null) {
      val current = DemoData.snapshot(router = demo.actionRouter).states[entityId]?.state ?: return
      demo.actionRouter.applyToggle(entityId, current)
      cache.clear()
    } else {
      // `homeassistant.toggle` flips on/off across every domain
      // that supports it, so we don't have to special-case
      // light vs switch vs input_boolean per entity prefix.
      launchService(domain = "homeassistant", service = "toggle", entityId = entityId)
    }
  }

  private fun handleCallService(action: HaAction.CallService) {
    val demo = session as? DemoHaSession
    if (demo != null) {
      applyDemoCallService(demo, action)
    } else {
      launchService(
        domain = action.domain,
        service = action.service,
        entityId = action.entityId,
        serviceData = action.serviceData,
      )
    }
  }

  private fun applyDemoCallService(demo: DemoHaSession, action: HaAction.CallService) {
    val entityId = action.entityId ?: return
    when (action.domain) {
      "cover" -> {
        val command =
          when (action.service) {
            "open_cover" -> CoverCommand.Open
            "close_cover" -> CoverCommand.Close
            "stop_cover" -> CoverCommand.Stop
            else -> return
          }
        val snapshot = DemoData.snapshot(router = demo.actionRouter)
        val position = snapshot.states[entityId]?.demoCoverPositionPercent() ?: 0
        demo.actionRouter.requestCover(
          entityId = entityId,
          command = command,
          nowMs = System.currentTimeMillis(),
          currentPosition = position,
        )
        cache.clear()
      }
      "homeassistant",
      "switch",
      "light",
      "input_boolean" -> {
        if (action.service in TOGGLE_LIKE_SERVICES) {
          val current =
            DemoData.snapshot(router = demo.actionRouter).states[entityId]?.state ?: return
          demo.actionRouter.applyToggle(entityId, current)
          cache.clear()
        }
      }
      else -> Log.i(TAG, "Demo router has no handler for ${action.domain}.${action.service}")
    }
  }

  private fun launchService(
    domain: String,
    service: String,
    entityId: String?,
    serviceData: kotlinx.serialization.json.JsonObject =
      kotlinx.serialization.json.JsonObject(emptyMap()),
  ) {
    scope.launch {
      runCatching {
        session.callService(
          domain = domain,
          service = service,
          entityId = entityId,
          serviceData = serviceData,
        )
      }
        .onFailure { e ->
          Log.w(TAG, "call_service $domain.$service failed for $entityId", e)
          onFailure()
        }
    }
  }

  companion object {
    private const val TAG = "DashboardAction"
    private val TOGGLE_LIKE_SERVICES = setOf("toggle", "turn_on", "turn_off")
  }
}
