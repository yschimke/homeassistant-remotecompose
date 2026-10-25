package ee.schimke.terrazzo.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** HA's history-graph default window. */
private const val DEFAULT_HISTORY_HOURS = 24

/** A week: more than any wall panel needs, and a bound on what one fetch asks the recorder for. */
private const val MAX_HISTORY_HOURS = 24 * 7

/** How often the recorder is asked again; the live state keeps each line's end current between. */
private val HISTORY_REFRESH = 5.minutes

/** How long the first fetch may hold the dashboard back before it shows without history. */
private val FIRST_HISTORY_WAIT = 5.seconds

/**
 * Each entity a history-graph card on [dashboard] plots, with the most hours any of them shows:
 * stacks, grids, conditionals and Remote Compose cards included.
 */
internal fun historyWindows(dashboard: Dashboard): Map<String, Int> {
  val windows = mutableMapOf<String, Int>()
  fun walk(raw: JsonObject) {
    if ((raw["type"] as? JsonPrimitive)?.contentOrNull == CardTypes.HISTORY_GRAPH) {
      val hours = historyHours(raw)
      (raw["entities"] as? JsonArray).orEmpty().forEach { row ->
        val id =
          when (row) {
            is JsonPrimitive -> row.contentOrNull
            is JsonObject -> (row["entity"] as? JsonPrimitive)?.contentOrNull
            else -> null
          } ?: return@forEach
        windows[id] = maxOf(windows[id] ?: 0, hours)
      }
    }
    (raw["cards"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::walk) }
    (raw["card"] as? JsonObject)?.let(::walk)
  }
  for (view in dashboard.views) {
    (view.cards + view.sections.flatMap { it.cards } + listOfNotNull(view.header?.card)).forEach {
      walk(it.raw)
    }
  }
  return windows
}

/**
 * The recorder history [dashboard]'s history graphs plot, fetched with [client] and again every
 * [HISTORY_REFRESH]. Null until the first fetch answers, fails or takes longer than
 * [FIRST_HISTORY_WAIT], so the dashboard can show its graphs with data on their first frame; empty
 * for a dashboard without history graphs.
 */
@Composable
internal fun rememberDashboardHistory(
  client: HaClient,
  dashboard: Dashboard?,
): Map<String, List<HistoryPoint>>? {
  val windows = remember(dashboard) { dashboard?.let(::historyWindows) }
  val history by
    produceState<Map<String, List<HistoryPoint>>?>(null, client, windows) {
      value = null
      if (windows == null) return@produceState
      if (windows.isEmpty()) {
        value = emptyMap()
        return@produceState
      }
      suspend fun fetch(): Map<String, List<HistoryPoint>> {
        val now = Clock.System.now()
        return buildMap {
          windows.entries.groupBy({ it.value }, { it.key }).forEach { (hours, ids) ->
            putAll(client.fetchHistory(ids, now - hours.hours, now))
          }
        }
      }
      var first = true
      while (true) {
        try {
          val fetched = if (first) withTimeoutOrNull(FIRST_HISTORY_WAIT) { fetch() } else fetch()
          value = fetched ?: value ?: emptyMap()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Keep what was shown; the next round asks again.
          if (value == null) value = emptyMap()
        }
        first = false
        delay(HISTORY_REFRESH)
      }
    }
  return history
}

/**
 * [snapshot] with [history], each series ending at its entity's current state: between fetches a
 * line follows the live value through its bindings, as Home Assistant's history graph does.
 */
internal fun withHistory(
  snapshot: HaSnapshot,
  history: Map<String, List<HistoryPoint>>,
): HaSnapshot {
  if (history.isEmpty()) return snapshot
  return snapshot.copy(
    history =
      snapshot.history +
        history.mapValues { (id, points) ->
          val state = snapshot.states[id] ?: return@mapValues points
          val at = state.lastUpdated ?: state.lastChanged ?: return@mapValues points
          if (points.isNotEmpty() && points.last().ts >= at) points
          else points + HistoryPoint(at, state.state)
        }
  )
}

/** The hours a history-graph config shows: its `hours_to_show`, within a week; HA's 24 without. */
private fun historyHours(raw: JsonObject): Int =
  (raw["hours_to_show"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, MAX_HISTORY_HOURS)
    ?: DEFAULT_HISTORY_HOURS

/**
 * [snapshot] for [card]: a history graph's series cut to its own `hours_to_show` before [now], as
 * the dashboard fetches each entity's longest window once for every graph that shows it. The last
 * point before the window stays, moved to its start, so a value that held through it still draws.
 * Any other card gets [snapshot] as it is.
 */
internal fun withCardHistoryWindow(
  card: CardConfig,
  snapshot: HaSnapshot,
  now: Instant = Clock.System.now(),
): HaSnapshot {
  if (card.type != CardTypes.HISTORY_GRAPH || snapshot.history.isEmpty()) return snapshot
  val start = now - historyHours(card.raw).hours
  return snapshot.copy(
    history =
      snapshot.history.mapValues { (_, points) ->
        val before = points.lastOrNull { it.ts < start }
        val inside = points.filter { it.ts >= start }
        if (before == null) inside else listOf(before.copy(ts = start)) + inside
      }
  )
}
