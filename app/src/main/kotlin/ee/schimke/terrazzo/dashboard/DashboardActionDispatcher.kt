package ee.schimke.terrazzo.dashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ee.schimke.ha.rc.CardDocumentCache
import ee.schimke.ha.rc.HaActionDispatcher
import ee.schimke.ha.rc.LocalCardDocumentCache
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.core.session.CoverCommand
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.demoCoverPositionPercent

/**
 * Build the [HaActionDispatcher] that backs `LocalHaActionDispatcher`
 * for the dashboard surface. The dispatcher fans actions out by type:
 *
 *   - [HaAction.Url] → `Intent.ACTION_VIEW` (opens a browser)
 *   - [HaAction.CallService] / [HaAction.Toggle] → demo router for
 *     [DemoHaSession]; logged-only for live sessions until the live
 *     client gains a service-call API.
 *   - [HaAction.Navigate] / [HaAction.MoreInfo] → logged for now —
 *     these need in-app navigation that the dashboard doesn't expose yet.
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
    return remember(session, context, cache) {
        DashboardActionDispatcher(context.applicationContext, session, cache)
    }
}

private class DashboardActionDispatcher(
    private val context: Context,
    private val session: HaSession,
    private val cache: CardDocumentCache,
) : HaActionDispatcher {
    override fun dispatch(action: HaAction) {
        when (action) {
            is HaAction.Url -> launchUrl(action.url)
            is HaAction.Toggle -> applyDemoToggle(action.entityId)
            is HaAction.CallService -> applyDemoCallService(action)
            is HaAction.MoreInfo,
            is HaAction.Navigate,
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

    private fun applyDemoToggle(entityId: String) {
        val demo = session as? DemoHaSession ?: run {
            Log.i(TAG, "Toggle for $entityId — live HA service call not implemented yet")
            return
        }
        // Snapshot once so we can read the entity's current state, then
        // bounce the request through the router.
        val snapshot = ee.schimke.terrazzo.core.session.DemoData.snapshot(
            router = demo.actionRouter,
        )
        val current = snapshot.states[entityId]?.state ?: return
        demo.actionRouter.applyToggle(entityId, current)
        cache.clear()
    }

    private fun applyDemoCallService(action: HaAction.CallService) {
        val demo = session as? DemoHaSession ?: run {
            Log.i(TAG, "CallService ${action.domain}.${action.service} — live not implemented yet")
            return
        }
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
                if (action.service == "toggle" || action.service == "turn_on" || action.service == "turn_off") {
                    val snapshot = ee.schimke.terrazzo.core.session.DemoData.snapshot(
                        router = demo.actionRouter,
                    )
                    val current = snapshot.states[entityId]?.state ?: return
                    demo.actionRouter.applyToggle(entityId, current)
                    cache.clear()
                }
            }
            else -> Log.i(TAG, "Demo router has no handler for ${action.domain}.${action.service}")
        }
    }

    companion object {
        private const val TAG = "DashboardAction"
    }
}
