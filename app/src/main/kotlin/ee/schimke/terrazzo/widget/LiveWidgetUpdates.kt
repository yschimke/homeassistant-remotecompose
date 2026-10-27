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
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.terrazzoGraph
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the launcher and Wear widgets showing Home Assistant's current states
 * (`docs/architecture/live-widget-updates.md`).
 *
 * The Terrazzo integration is told which entities the widgets read and wakes the app when one of
 * them visibly changes ([WidgetStateWorker]). While a session follows them here ([follow]), it
 * holds a lease there, so nothing wakes the app meanwhile, and redraws the widgets itself: at once
 * for a visible edge ([WidgetEdges]), at most once a [QUIET_REDRAW_MS] for a number ticking or an
 * attribute.
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
  private const val CHECK_MS = 1_000L

  /** The entities the launcher widgets on [baseUrl] and the watch's cards read. */
  suspend fun watchedEntities(context: Context, baseUrl: String): Set<String> {
    val graph = context.terrazzoGraph()
    val launcher =
      graph.widgetStore.installed
        .first()
        .filter { it.baseUrl == baseUrl }
        .flatMapTo(mutableSetOf()) { referencedEntities(it.card) }
    return launcher + graph.wearSyncManager.watchedEntities()
  }

  /**
   * Follows [session]'s states for registration [webhookId] and redraws the widgets as they change,
   * holding the integration's lease, until [stop] answers true for the entities followed (as last
   * drawn) and the time since they last changed, or the caller is cancelled. Returns whether the
   * integration took the lease: without it (no integration, or one older than state wakes) only an
   * open app keeps widgets current.
   */
  suspend fun follow(
    context: Context,
    session: HaSession,
    webhookId: String,
    stop: suspend (watched: Map<String, EntityState?>, sinceChangeMs: Long) -> Boolean = { _, _ ->
      false
    },
  ): Boolean = coroutineScope {
    if (DemoData.isDemo(session.baseUrl)) return@coroutineScope false
    session.connectionStatus.first { it == SessionConnectionStatus.Connected }
    var ids = watchedEntities(context, session.baseUrl)
    var leased = false
    // Best effort: a dropped socket fails one renewal, and the next reconnected one takes it again.
    suspend fun lease() {
      ids = watchedEntities(context, session.baseUrl)
      try {
        leased = session.watchStates(webhookId, ids, LEASE_SECONDS) != null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Log.d(TAG, "Couldn't renew the widget lease", failure)
      }
    }
    lease()
    val renew = launch {
      while (true) {
        delay(RENEW_MS)
        lease()
      }
    }
    // A widget pinned meanwhile is watched now, not at the next renewal: the app may be gone by
    // then.
    val pinned = launch {
      context.terrazzoGraph().widgetStore.installed.drop(1).collect { lease() }
    }
    var drawn: Map<String, EntityState?>? = null
    var latest: Pair<HaSnapshot, Map<String, EntityState?>>? = null
    var drawnAt = 0L
    var changedAt = System.currentTimeMillis()
    val follower = launch {
      session.liveStates
        .filterNotNull()
        .map { snapshot -> snapshot to ids.associateWith { snapshot.states[it] } }
        .distinctUntilChanged { a, b -> a.second == b.second }
        .collectLatest { (snapshot, watched) ->
          latest = snapshot to watched
          val before = drawn
          changedAt = System.currentTimeMillis()
          if (before != null) {
            val visible = watched.keys.any { WidgetEdges.isVisible(it, before[it], watched[it]) }
            val gap = if (visible) VISIBLE_REDRAW_MS else QUIET_REDRAW_MS
            // From the last redraw, so a stream of changes can't put this one off for ever.
            val wait = drawnAt + gap - System.currentTimeMillis()
            if (wait > 0) delay(wait)
          }
          val changed = watched.keys.filter { before?.get(it) != watched[it] }.toSet()
          redraw(context, session.baseUrl, snapshot, changed.takeIf { before != null })
          drawn = watched
          drawnAt = System.currentTimeMillis()
        }
    }
    try {
      while (!stop(drawn.orEmpty(), System.currentTimeMillis() - changedAt)) delay(CHECK_MS)
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
      renew.cancel()
      pinned.cancel()
      // Released, so the next visible change wakes the app at once rather than after the lease.
      withContext(NonCancellable) {
        withTimeoutOrNull(2_000L) { runCatching { session.watchStates(webhookId, ids, 0) } }
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
        }
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
