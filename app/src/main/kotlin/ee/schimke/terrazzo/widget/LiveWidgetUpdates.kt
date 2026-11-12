package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.logs.referencedEntities
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.ReconnectBackoff
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.terrazzoGraph
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the launcher and Wear widgets showing Home Assistant's current states
 * (`docs/architecture/live-widget-updates.md`).
 *
 * The Terrazzo integration is told which entities the widgets with live updates on read
 * ([watchedEntities]) and wakes the app when one of them visibly changes ([WidgetStateWorker]).
 * While a session follows them here ([follow]), it holds a lease there, so nothing wakes the app
 * meanwhile, and redraws the widgets itself: at once for a visible edge ([WidgetEdges]), at most
 * once a [QUIET_REDRAW_MS] for a number ticking or an attribute.
 */
object LiveWidgetUpdates {
  private const val TAG = "LiveWidgetUpdates"

  /** The lease asked of the integration, and how often it is renewed well inside it. */
  private const val LEASE_SECONDS = 90
  private const val RENEW_MS = 45_000L

  /** The quickest redraws follow one another, for a visible edge and for anything else. */
  private const val VISIBLE_REDRAW_MS = 1_000L
  private const val QUIET_REDRAW_MS = 60_000L

  /** How often [follow] asks its `stop` whether the widgets have settled. */
  internal var checkMs: Long = 1_000L
  internal var visibleRedrawMs: Long = VISIBLE_REDRAW_MS
  internal var quietRedrawMs: Long = QUIET_REDRAW_MS
  internal var reconnectInitialMs: Long = 1_000L
  internal var reconnectMaxMs: Long = 15_000L
  internal var clock: () -> Long = System::currentTimeMillis

  internal fun resetTimingForTest() {
    checkMs = 1_000L
    visibleRedrawMs = VISIBLE_REDRAW_MS
    quietRedrawMs = QUIET_REDRAW_MS
    reconnectInitialMs = 1_000L
    reconnectMaxMs = 15_000L
    clock = System::currentTimeMillis
  }

  /**
   * The entities read by the widgets with live updates on: the launcher widgets on [baseUrl] and
   * the watch widgets. Live updates are per widget and off until the user turns them on, so with
   * none on this is empty and nothing wakes the app.
   */
  suspend fun watchedEntities(context: Context, baseUrl: String): Set<String> {
    val graph = context.terrazzoGraph()
    val launcher =
      graph.widgetStore.installed
        .first()
        .filter { it.baseUrl == baseUrl && it.liveUpdates }
        .flatMapTo(mutableSetOf()) { referencedEntities(it.card) }
    return launcher + graph.wearSyncManager.watchedEntities()
  }

  /**
   * The entities read by every launcher widget on [baseUrl], live updates on or off, and by the
   * live watch widgets: what a connected session redraws as they change. A widget without live
   * updates still keeps up while the app happens to be running.
   */
  suspend fun shownEntities(context: Context, baseUrl: String): Set<String> {
    val graph = context.terrazzoGraph()
    val launcher =
      graph.widgetStore.installed
        .first()
        .filter { it.baseUrl == baseUrl }
        .flatMapTo(mutableSetOf()) { referencedEntities(it.card) }
    return launcher + graph.wearSyncManager.watchedEntities()
  }

  /**
   * Whether any entity shown on a widget of [baseUrl] is currently passing through a moving state
   * (`closing`, `opening`, `locking`, `unlocking`, `arming`, …) in [snapshot] (or the cached one).
   */
  suspend fun hasMovingEntity(
    context: Context,
    baseUrl: String,
    snapshot: HaSnapshot? = context.terrazzoGraph().offlineCache.latestSnapshot(baseUrl),
  ): Boolean {
    if (snapshot == null || DemoData.isDemo(baseUrl)) return false
    return shownEntities(context, baseUrl).any { WidgetEdges.isMoving(snapshot.states[it]) }
  }

  /**
   * Parks the foreground [session] when the app leaves `RESUMED`: releases its integration lease
   * while the socket is still open, disconnects cleanly, and hands off to [WidgetStateWorker] if
   * any shown widget entity is mid-movement (a garage door still `closing`) so the widget isn't
   * left showing that temporary state.
   */
  suspend fun parkAndHandOff(context: Context, session: HaSession) {
    val appContext = context.applicationContext
    val baseUrl = session.baseUrl
    val liveSnapshot = session.liveStates.value
    if (!DemoData.isDemo(baseUrl)) {
      if (liveSnapshot != null) {
        appContext.terrazzoGraph().offlineCache.putLatestStates(baseUrl, liveSnapshot)
      }
      if (session.connectionStatus.value == SessionConnectionStatus.Connected) {
        val webhookId = appContext.terrazzoGraph().mobileAppStore.get(baseUrl)?.webhookId
        if (webhookId != null) {
          val ids = watchedEntities(appContext, baseUrl)
          withTimeoutOrNull(2_000L) { runCatching { session.watchStates(webhookId, ids, 0) } }
        }
      }
    }
    runCatching { session.disconnect() }
    if (hasMovingEntity(appContext, baseUrl)) {
      WidgetStateWorker.ensureFollowing(appContext, baseUrl, resetCooldown = true)
    }
  }

  /**
   * Follows [session]'s states for registration [webhookId] (looked up from [baseUrl] when null)
   * and redraws the widgets as they change (every one: [shownEntities]), holding the integration's
   * lease for the live ones ([watchedEntities]), until [stop] answers true for the entities
   * followed (as last drawn) and the time since they last visibly changed, or the caller is
   * cancelled. With [reconnect], a background window ([WidgetStateWorker]) reopens the socket when
   * it drops mid-movement (for instance, leaving home Wi-Fi onto cellular while a garage door
   * closes). Returns whether the integration took the lease.
   */
  suspend fun follow(
    context: Context,
    session: HaSession,
    webhookId: String? = null,
    reconnect: Boolean = false,
    stop: suspend (watched: Map<String, EntityState?>?, sinceChangeMs: Long) -> Boolean = { _, _ ->
      false
    },
  ): Boolean = coroutineScope {
    if (DemoData.isDemo(session.baseUrl)) return@coroutineScope false
    val graph = context.terrazzoGraph()
    val reconnector =
      if (!reconnect) null
      else
        launch {
          val backoff =
            ReconnectBackoff(
              initial = reconnectInitialMs.milliseconds,
              max = reconnectMaxMs.milliseconds,
            )
          val networkChanges = graph.lanConnectionPolicy.networkChanges
          while (true) {
            session.connectionStatus.first { it == SessionConnectionStatus.Failed }
            if (session.needsReauth.value) break
            val network = networkChanges.value
            withTimeoutOrNull(backoff.next()) { networkChanges.first { it != network } }
            if (session.connectionStatus.value == SessionConnectionStatus.Failed) {
              runCatching { session.connect() }
            }
            if (session.connectionStatus.value == SessionConnectionStatus.Connected) {
              backoff.reset()
            }
          }
        }
    var ids = watchedEntities(context, session.baseUrl)
    var followed = shownEntities(context, session.baseUrl)
    var leased = false
    var activeWebhookId = webhookId
    // Best effort: a dropped socket fails one renewal, and reconnecting takes the lease again.
    suspend fun lease() {
      ids = watchedEntities(context, session.baseUrl)
      followed = shownEntities(context, session.baseUrl)
      val hook = activeWebhookId ?: graph.mobileAppStore.get(session.baseUrl)?.webhookId
      activeWebhookId = hook
      if (hook == null || session.connectionStatus.value != SessionConnectionStatus.Connected) {
        return
      }
      try {
        leased = session.watchStates(hook, ids, LEASE_SECONDS) != null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Log.d(TAG, "Couldn't renew the widget lease", failure)
      }
    }
    val renew = launch {
      session.connectionStatus
        .map { it == SessionConnectionStatus.Connected }
        .distinctUntilChanged()
        .collectLatest { connected ->
          if (!connected) return@collectLatest
          while (true) {
            lease()
            delay(RENEW_MS)
          }
        }
    }
    // A widget pinned, or live updates turned on or off, meanwhile is watched (or not) now, not at
    // the next renewal: the app may be gone by then.
    val pinned = launch {
      merge(graph.widgetStore.installed.drop(1), graph.wearSyncManager.watchWidgets.drop(1))
        .collect { lease() }
    }
    var drawn: Map<String, EntityState?>? = null
    var latest: Pair<HaSnapshot, Map<String, EntityState?>>? = null
    var drawnAt = 0L
    var changedAt = clock()
    val follower = launch {
      session.liveStates
        .filterNotNull()
        .map { snapshot -> snapshot to followed.associateWith { snapshot.states[it] } }
        .distinctUntilChanged { a, b -> a.second == b.second }
        .collectLatest { (snapshot, watched) ->
          latest = snapshot to watched
          val before = drawn
          val visible =
            before == null ||
              watched.keys.any { WidgetEdges.isVisible(it, before[it], watched[it]) }
          if (visible) changedAt = clock()
          if (before != null) {
            val gap = if (visible) visibleRedrawMs else quietRedrawMs
            // From the last redraw, so a stream of changes can't put this one off for ever.
            val wait = drawnAt + gap - clock()
            if (wait > 0) delay(wait)
          }
          val changed = watched.keys.filter { before?.get(it) != watched[it] }.toSet()
          redraw(context, session.baseUrl, snapshot, changed.takeIf { before != null })
          drawn = watched
          drawnAt = clock()
        }
    }
    try {
      while (!stop(drawn, clock() - changedAt)) delay(checkMs)
      follower.cancelAndJoin()
      // A quiet change still waiting for its redraw: draw it now, as no wake will come for it.
      latest
        ?.takeIf { (_, watched) -> watched != drawn }
        ?.let { (snapshot, watched) ->
          val before = drawn
          val changed = watched.keys.filter { before?.get(it) != watched[it] }.toSet()
          redraw(context, session.baseUrl, snapshot, changed.takeIf { before != null })
        }
    } finally {
      reconnector?.cancel()
      renew.cancel()
      pinned.cancel()
      // Released, so the next visible change wakes the app at once rather than after the lease.
      val hook = activeWebhookId
      if (hook != null && session.connectionStatus.value == SessionConnectionStatus.Connected) {
        withContext(NonCancellable) {
          withTimeoutOrNull(2_000L) { runCatching { session.watchStates(hook, ids, 0) } }
        }
      }
    }
    leased
  }

  /**
   * Draws [snapshot] into the launcher widgets on [baseUrl] that read one of [changed] (every one
   * for null) and sends the watch its cards' values.
   */
  suspend fun redraw(
    context: Context,
    baseUrl: String,
    snapshot: HaSnapshot,
    changed: Set<String>? = null,
  ) {
    val graph = context.terrazzoGraph()
    graph.offlineCache.putLatestStates(baseUrl, snapshot)
    val ids =
      graph.widgetStore.installed
        .first()
        .filter { it.baseUrl == baseUrl }
        .filter { changed == null || referencedEntities(it.card).any(changed::contains) }
        .map { it.widgetId }
        .toIntArray()
    if (ids.isNotEmpty()) {
      context.sendBroadcast(
        Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
          component = ComponentName(context, TerrazzoWidgetProvider::class.java)
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        },
      )
    }
    try {
      graph.wearSyncManager.pushValues(snapshot)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Log.w(TAG, "Couldn't send the watch its values", failure)
    }
  }
}

/**
 * What a person would notice on a widget, as the Terrazzo integration's `state_wakes.py` judges it:
 * the state flipping, not an attribute or a number ticking; and a movement (a garage door
 * `opening`) worth staying connected for.
 */
object WidgetEdges {
  private val MOVING =
    mapOf(
      "cover" to setOf("opening", "closing"),
      "valve" to setOf("opening", "closing"),
      "lock" to setOf("locking", "unlocking", "opening"),
      "alarm_control_panel" to setOf("arming", "disarming", "pending"),
    )

  /** Whether [entityId]'s domain passes through moving states (`cover`, `valve`, `lock`, …). */
  fun movesDomain(entityId: String): Boolean = entityId.substringBefore('.') in MOVING

  /** Whether [entity] is passing through a state it only holds while it moves. */
  fun isMoving(entity: EntityState?): Boolean =
    entity != null && entity.state in MOVING[entity.entityId.substringBefore('.')].orEmpty()

  /** Whether [entityId] going from [before] to [after] is a change a glance would catch. */
  fun isVisible(entityId: String, before: EntityState?, after: EntityState?): Boolean {
    val from = before?.state
    val to = after?.state
    if (from == to) return false
    if (isMoving(before) || isMoving(after)) return true
    // A number ticking (a power sensor) only redraws on the quiet cadence.
    return from?.toDoubleOrNull() == null || to?.toDoubleOrNull() == null
  }
}
