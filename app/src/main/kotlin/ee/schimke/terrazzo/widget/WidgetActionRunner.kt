package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import ee.schimke.ha.client.CommandNotSentException
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a launcher widget's toggle or service call against Home Assistant from the background: the
 * widget's whole point is acting without opening the app.
 *
 * The write rule: only an action the app put on the widget's own `PendingIntent`
 * ([trustedWidgetAction]), only for a widget that is still installed, only on that widget's own
 * server, and only a toggle or a service call. A pinned widget is a deliberate control, so the
 * dashboard's read-only lock (a guard against stray taps while scrolling) doesn't apply.
 *
 * The connection is the app's own login. While the app runs with a connected session for the
 * widget's server, the call goes on that socket ([setAppSession]). Otherwise the stored refresh
 * token mints an access token (kept until it expires), and a short-lived session sends
 * `call_service` and closes. An on/off action shows on the widget at once (its cached state flipped
 * and the widget redrawn), and is put back if the call fails.
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
    // Only the tapped entity changes, atomically with any live refresh writing the same snapshot.
    var before: String? = null
    var expected: String? = null
    val shown =
      graph.offlineCache.updateLatestState(entry.baseUrl, entityId) { current ->
        optimisticState(action, current)?.also {
          before = current
          expected = it
        }
      }
    if (shown) redraw(context, widgetId)
    val result = runCatching { withTimeout(CALL_TIMEOUT_MS) { call(graph, entry.baseUrl, action) } }
    result.exceptionOrNull()?.let { failure ->
      Log.w(TAG, "Widget $widgetId action on $entityId failed", failure)
      graph.logStore.recordLocalAction("Widget action failed", entityId)
      val original = before
      // Put it back only if it still shows this tap's guess: a newer live state stands.
      if (
        shown &&
          original != null &&
          graph.offlineCache.updateLatestState(entry.baseUrl, entityId) { current ->
            original.takeIf { current == expected }
          }
      ) {
        redraw(context, widgetId)
      }
    }
  }

  /**
   * The app's own session while it runs, from `TerrazzoApp`. A tap on a widget of the same server
   * sends its call on that socket while it is connected, skipping the token refresh, connect and
   * auth round trips; it is the signed-in session by definition (signing out clears it).
   */
  @Volatile private var appSession: HaSession? = null

  fun setAppSession(session: HaSession?) {
    appSession = session
  }

  /** The app's own session for [baseUrl] while it is connected, else null. */
  fun connectedAppSession(baseUrl: String): HaSession? = appSession?.takeIf {
    it.baseUrl == baseUrl && it.connectionStatus.value == SessionConnectionStatus.Connected
  }

  private suspend fun call(graph: TerrazzoGraph, baseUrl: String, action: HaAction) {
    val started = System.nanoTime()
    val live = connectedAppSession(baseUrl)
    // The app's socket may be closing as it is picked (the app just went to the background). A call
    // it refused before sending goes on a new session; any other failure isn't retried, since Home
    // Assistant may have run it with only the reply lost, and a second toggle would undo the first.
    val path =
      if (live == null) {
        callOnNewSession(graph, baseUrl, action)
        "a new session"
      } else {
        try {
          send(live, action)
          "the app's session"
        } catch (notSent: CommandNotSentException) {
          callOnNewSession(graph, baseUrl, action)
          "a new session (the app's was closing)"
        }
      }
    Log.i(
      TAG,
      "Widget ${actionEntityId(action)} on $path in ${(System.nanoTime() - started) / 1_000_000} ms",
    )
  }

  private suspend fun send(session: HaSession, action: HaAction) {
    when (action) {
      // `homeassistant.toggle` flips on/off across every domain that supports it.
      is HaAction.Toggle ->
        session.callService("homeassistant", "toggle", entityId = action.entityId)
      is HaAction.CallService ->
        session.callService(action.domain, action.service, action.entityId, action.serviceData)
      else -> Unit
    }
  }

  private suspend fun callOnNewSession(graph: TerrazzoGraph, baseUrl: String, action: HaAction) {
    val session = graph.sessionFactory.create(baseUrl, accessToken(graph, baseUrl))
    try {
      session.connect()
      send(session, action)
    } catch (failure: Throwable) {
      // A refused token is dropped, so the next tap mints a fresh one.
      forgetAccessToken(baseUrl)
      throw failure
    } finally {
      // A timed-out call is cancelled; the socket must still close, so close outside that.
      withContext(NonCancellable) {
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runCatching { session.close() } }
      }
    }
  }

  /** A minted access token, the refresh token it was minted from, and when to stop using it. */
  private class CachedToken(val refreshToken: String, val accessToken: String, val until: Long)

  private val tokensMutex = Mutex()
  private val tokens = HashMap<String, CachedToken>()

  /**
   * An access token for [baseUrl]: the cached one until shortly before it expires, and only while
   * the vault still holds the refresh token it came from. Signing out clears the vault, and signing
   * in again (another user too) stores a new one, so a widget never acts on an old login. Shared
   * with the AppFunctions service, which acts from the background the same way.
   */
  suspend fun accessToken(graph: TerrazzoGraph, baseUrl: String): String = tokensMutex.withLock {
    val refresh = graph.tokenVault.get(baseUrl)
    if (refresh == null) {
      tokens.remove(baseUrl)
      error("Signed out of $baseUrl: no refresh token")
    }
    val now = System.currentTimeMillis()
    tokens[baseUrl]
      ?.takeIf { it.refreshToken == refresh && it.until > now }
      ?.let {
        return@withLock it.accessToken
      }
    val minted = graph.authService.refreshAccessToken(baseUrl, refresh)
    val stored = minted.refreshToken?.also { graph.tokenVault.put(baseUrl, it) } ?: refresh
    val token = minted.accessToken ?: error("Token refresh for $baseUrl returned no token")
    val lifetimeMs = (minted.expiresInSeconds ?: DEFAULT_TOKEN_LIFETIME_S) * 1000
    tokens[baseUrl] = CachedToken(stored, token, now + lifetimeMs - TOKEN_EXPIRY_MARGIN_MS)
    token
  }

  /** Drops [baseUrl]'s cached access token after a failed call, so the next one mints afresh. */
  suspend fun forgetAccessToken(baseUrl: String) {
    tokensMutex.withLock { tokens.remove(baseUrl) }
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
  private const val CLOSE_TIMEOUT_MS = 2_000L
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
 * The state [action] puts its entity in from [current], for actions whose result is plain on/off: a
 * toggle of an `on` / `off` entity, a `turn_on` / `turn_off` call. Null for anything else (a cover
 * moving, a value set), which the widget shows once a live session reports it.
 */
internal fun optimisticState(action: HaAction, current: String): String? {
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
