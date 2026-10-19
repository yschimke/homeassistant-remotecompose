package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Runs a launcher widget's toggle or service call against Home Assistant from the background: the
 * widget's whole point is acting without opening the app.
 *
 * The write rule: only an action the app put on the widget's own `PendingIntent`
 * ([trustedWidgetAction]), only for a widget that is still installed, only on that widget's own
 * server, and only a toggle or a service call. A pinned widget is a deliberate control, so the
 * dashboard's read-only lock (a guard against stray taps while scrolling) doesn't apply.
 *
 * The connection is the app's own login: the stored refresh token mints an access token (kept until
 * it expires), and a short-lived session sends `call_service` and closes. An on/off action shows on
 * the widget at once (its cached state flipped and the widget redrawn), and is put back if the call
 * fails.
 */
internal object WidgetActionRunner {

  /** Whether [action] is one this runner performs; the rest only open the app or are logged. */
  fun runs(action: HaAction): Boolean =
    action is HaAction.Toggle || (action is HaAction.CallService && action.entityId != null)

  suspend fun run(context: Context, widgetId: Int, action: HaAction) {
    if (!runs(action)) return
    val graph = context.terrazzoGraph()
    val entry = graph.widgetStore.get(widgetId)
    if (entry == null) {
      Log.w(TAG, "Refusing an action for widget $widgetId: it is not installed")
      return
    }
    if (DemoData.isDemo(entry.baseUrl)) {
      Log.i(TAG, "Demo widget $widgetId: no Home Assistant to call")
      return
    }
    val entityId = actionEntityId(action) ?: return
    val before = graph.offlineCache.latestSnapshot(entry.baseUrl)
    val expected = before?.let { optimisticState(action, it) }
    if (before != null && expected != null) {
      graph.offlineCache.putLatestSnapshot(entry.baseUrl, before.withState(entityId, expected))
      redraw(context, widgetId)
    }
    val result = runCatching { withTimeout(CALL_TIMEOUT_MS) { call(graph, entry.baseUrl, action) } }
    result.exceptionOrNull()?.let { failure ->
      Log.w(TAG, "Widget $widgetId action on $entityId failed", failure)
      graph.logStore.recordLocalAction("Widget action failed", entityId)
      if (before != null && expected != null) {
        graph.offlineCache.putLatestSnapshot(entry.baseUrl, before)
        redraw(context, widgetId)
      }
    }
  }

  private suspend fun call(graph: TerrazzoGraph, baseUrl: String, action: HaAction) {
    val session = graph.sessionFactory.create(baseUrl, accessToken(graph, baseUrl))
    try {
      session.connect()
      when (action) {
        // `homeassistant.toggle` flips on/off across every domain that supports it.
        is HaAction.Toggle ->
          session.callService("homeassistant", "toggle", entityId = action.entityId)
        is HaAction.CallService ->
          session.callService(action.domain, action.service, action.entityId, action.serviceData)
        else -> Unit
      }
    } catch (failure: Throwable) {
      // A refused token is dropped, so the next tap mints a fresh one.
      tokensMutex.withLock { tokens.remove(baseUrl) }
      throw failure
    } finally {
      runCatching { session.close() }
    }
  }

  private val tokensMutex = Mutex()
  private val tokens = HashMap<String, Pair<String, Long>>()

  /** An access token for [baseUrl]: the cached one until shortly before it expires. */
  private suspend fun accessToken(graph: TerrazzoGraph, baseUrl: String): String =
    tokensMutex.withLock {
      val now = System.currentTimeMillis()
      tokens[baseUrl]
        ?.takeIf { (_, expiresAt) -> expiresAt > now }
        ?.let { (token, _) ->
          return@withLock token
        }
      val refresh =
        graph.tokenVault.get(baseUrl) ?: error("Signed out of $baseUrl: no refresh token")
      val minted = graph.authService.refreshAccessToken(baseUrl, refresh)
      minted.refreshToken?.let { graph.tokenVault.put(baseUrl, it) }
      val token = minted.accessToken ?: error("Token refresh for $baseUrl returned no token")
      val lifetimeMs = (minted.expiresInSeconds ?: DEFAULT_TOKEN_LIFETIME_S) * 1000
      tokens[baseUrl] = token to now + lifetimeMs - TOKEN_EXPIRY_MARGIN_MS
      token
    }

  private fun redraw(context: Context, widgetId: Int) {
    context.sendBroadcast(
      Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
        component = ComponentName(context, TerrazzoWidgetProvider::class.java)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
      }
    )
  }

  private const val TAG = "WidgetActionRunner"

  /** Under a broadcast's 10 s budget, with room for the token refresh. */
  private const val CALL_TIMEOUT_MS = 8_000L
  private const val DEFAULT_TOKEN_LIFETIME_S = 1_800L
  private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
}

/** The entity [action] targets, or null for one with none. */
internal fun actionEntityId(action: HaAction): String? =
  when (action) {
    is HaAction.Toggle -> action.entityId
    is HaAction.CallService -> action.entityId
    is HaAction.MoreInfo -> action.entityId
    is HaAction.AlarmKey -> action.entityId
    is HaAction.AlarmPin -> action.entityId
    is HaAction.AlarmIntent -> action.entityId
    is HaAction.Navigate,
    is HaAction.Url,
    HaAction.None -> null
  }

/**
 * The state [action] puts its entity in, for actions whose result is plain on/off: a toggle of an
 * `on` / `off` entity, a `turn_on` / `turn_off` call. Null for anything else (a cover moving, a
 * value set), which the widget shows once a live session reports it.
 */
internal fun optimisticState(action: HaAction, snapshot: HaSnapshot): String? {
  val entityId = actionEntityId(action) ?: return null
  val current = snapshot.states[entityId]?.state ?: return null
  val service =
    when (action) {
      is HaAction.Toggle -> "toggle"
      is HaAction.CallService -> action.service
      else -> return null
    }
  return when (service) {
    "turn_on" -> "on"
    "turn_off" -> "off"
    "toggle" ->
      when (current) {
        "on" -> "off"
        "off" -> "on"
        else -> null
      }
    else -> null
  }
}

private fun HaSnapshot.withState(entityId: String, state: String): HaSnapshot {
  val entity = states[entityId] ?: return this
  return copy(states = states + (entityId to entity.copy(state = state)))
}
