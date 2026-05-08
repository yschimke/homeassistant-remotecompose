package ee.schimke.terrazzo.dashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import ee.schimke.ha.rc.AlarmKeypadCoordinator
import ee.schimke.ha.rc.CardDocumentCache
import ee.schimke.ha.rc.HaActionDispatcher
import ee.schimke.ha.rc.LocalCardDocumentCache
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.core.session.CoverCommand
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.demoCoverPositionPercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Build the [HaActionDispatcher] that backs `LocalHaActionDispatcher`
 * for the dashboard surface. The dispatcher fans actions out by type:
 *
 *   - [HaAction.Url] → `Intent.ACTION_VIEW` (opens a browser)
 *   - [HaAction.Toggle] / [HaAction.CallService] → live `HaSession`
 *     calls through `call_service` (`homeassistant.toggle` for the
 *     bare `Toggle` form); demo sessions route through
 *     [DemoHaSession.actionRouter] so taps are visible without a
 *     network round-trip.
 *   - [HaAction.Navigate] / [HaAction.MoreInfo] → logged for now —
 *     these need in-app navigation that the dashboard doesn't expose
 *     yet.
 *
 * In demo mode the dispatcher also evicts the [CardDocumentCache] so
 * cards baking state into bytes (e.g. the garage shutter card's
 * `closedFraction`) re-encode against the new snapshot on the next
 * dashboard refresh — without that, toggles fire but the visual
 * doesn't update.
 */
@Composable
fun rememberDashboardActionDispatcher(session: HaSession): HaActionDispatcher {
    val context = LocalContext.current
    val cache = LocalCardDocumentCache.current
    val scope = rememberCoroutineScope()
    return remember(session, context, cache, scope) {
        val core = DashboardActionDispatcher(context.applicationContext, session, cache, scope)
        // The keypad coordinator buffers per-key host actions and
        // translates ARM intents into a single `call_service` with the
        // entered code attached. Other action variants pass through.
        AlarmKeypadCoordinator(core, scope)
    }
}

private class DashboardActionDispatcher(
    private val context: Context,
    private val session: HaSession,
    private val cache: CardDocumentCache,
    private val scope: CoroutineScope,
) : HaActionDispatcher {
    override fun dispatch(action: HaAction) {
        when (action) {
            is HaAction.Url -> launchUrl(action.url)
            is HaAction.Toggle -> handleToggle(action.entityId)
            is HaAction.CallService -> handleCallService(action)
            is HaAction.MoreInfo,
            is HaAction.Navigate,
            // AlarmKey / AlarmIntent should be intercepted upstream by
            // AlarmKeypadCoordinator; logging here means an alarm-panel
            // action escaped the coordinator (most likely a bug).
            is HaAction.AlarmKey,
            is HaAction.AlarmIntent,
            HaAction.None -> Log.i(TAG, "Action not yet wired: $action")
        }
    }

    private fun launchUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: run {
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
            val current = DemoData.snapshot(router = demo.actionRouter)
                .states[entityId]?.state
                ?: return
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
                val command = when (action.service) {
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
            "homeassistant", "switch", "light", "input_boolean" -> {
                if (action.service in TOGGLE_LIKE_SERVICES) {
                    val current = DemoData.snapshot(router = demo.actionRouter)
                        .states[entityId]?.state
                        ?: return
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
            }.onFailure { e ->
                Log.w(TAG, "call_service $domain.$service failed for $entityId", e)
            }
        }
    }

    companion object {
        private const val TAG = "DashboardAction"
        private val TOGGLE_LIKE_SERVICES = setOf("toggle", "turn_on", "turn_off")
    }
}
