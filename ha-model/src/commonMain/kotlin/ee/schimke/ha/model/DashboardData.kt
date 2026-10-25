package ee.schimke.ha.model

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * What a dashboard's data cards need beyond entity states, which a host loads into the snapshot it
 * draws: the recorder history its history graphs plot ([HaSnapshot.history]) and the forecasts its
 * weather cards show ([HaSnapshot.forecasts], each entity's forecast lists by type).
 */

/** HA's history-graph default window. */
const val DEFAULT_HISTORY_HOURS: Int = 24

/** A week: more than any dashboard needs, and a bound on what one fetch asks the recorder for. */
const val MAX_HISTORY_HOURS: Int = 24 * 7

/**
 * Each entity a history-graph card on [dashboard] plots, with the most hours any of them shows:
 * stacks, grids, conditionals and Remote Compose cards included.
 */
fun historyWindows(dashboard: Dashboard): Map<String, Int> {
  val windows = mutableMapOf<String, Int>()
  dashboard.forEachCardConfig { raw ->
    if (raw.type() != CardTypes.HISTORY_GRAPH) return@forEachCardConfig
    val hours = historyHours(raw)
    raw.entityIds().forEach { id -> windows[id] = maxOf(windows[id] ?: 0, hours) }
  }
  return windows
}

/**
 * The forecasts [dashboard]'s weather cards show: for each forecast type (`daily` by default,
 * `hourly`, `twice_daily`), the weather entities that need it. A card with `show_forecast: false`
 * needs none.
 */
fun forecastRequests(dashboard: Dashboard): Map<String, Set<String>> {
  val requests = mutableMapOf<String, MutableSet<String>>()
  dashboard.forEachCardConfig { raw ->
    if (raw.type() != CardTypes.WEATHER_FORECAST) return@forEachCardConfig
    if ((raw["show_forecast"] as? JsonPrimitive)?.booleanOrNull == false) return@forEachCardConfig
    val entity = (raw["entity"] as? JsonPrimitive)?.contentOrNull ?: return@forEachCardConfig
    val type = (raw["forecast_type"] as? JsonPrimitive)?.contentOrNull ?: "daily"
    requests.getOrPut(type) { mutableSetOf() } += entity
  }
  return requests
}

/** [byType] (forecast type → entity → its forecast list) as [HaSnapshot.forecasts] holds them. */
fun forecastsByEntity(byType: Map<String, Map<String, JsonArray>>): Map<String, JsonObject> {
  val entities = mutableMapOf<String, MutableMap<String, JsonArray>>()
  for ((type, forecasts) in byType) {
    for ((id, list) in forecasts) entities.getOrPut(id) { mutableMapOf() }[type] = list
  }
  return entities.mapValues { (_, lists) -> JsonObject(lists) }
}

/**
 * This snapshot with each history series ending at its entity's current state: between fetches a
 * line follows the live value through its bindings, as Home Assistant's history graph does.
 */
fun HaSnapshot.withLiveHistoryTail(): HaSnapshot {
  if (history.isEmpty()) return this
  return copy(
    history =
      history.mapValues { (id, points) ->
        val state = states[id] ?: return@mapValues points
        val at = state.lastUpdated ?: state.lastChanged ?: return@mapValues points
        if (points.isNotEmpty() && points.last().ts >= at) points
        else points + HistoryPoint(at, state.state)
      }
  )
}

/**
 * This snapshot for [card]: a history graph's series cut to its own `hours_to_show` before [now],
 * since a host fetches each entity's longest window once for every graph that shows it. The last
 * point before the window stays, moved to its start, so a value that held through it still draws.
 * Any other card gets the snapshot as it is.
 */
fun HaSnapshot.forCardHistoryWindow(card: CardConfig, now: Instant): HaSnapshot {
  if (card.type != CardTypes.HISTORY_GRAPH || history.isEmpty()) return this
  val start = now - historyHours(card.raw).hours
  return copy(
    history =
      history.mapValues { (_, points) ->
        val before = points.lastOrNull { it.ts < start }
        val inside = points.filter { it.ts >= start }
        if (before == null) inside else listOf(before.copy(ts = start)) + inside
      }
  )
}

/** The hours a history-graph config shows: its `hours_to_show`, within a week; HA's 24 without. */
private fun historyHours(raw: JsonObject): Int =
  (raw["hours_to_show"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, MAX_HISTORY_HOURS)
    ?: DEFAULT_HISTORY_HOURS

/** Every card config on the dashboard, nested ones (`cards:`, `card:`) included. */
private fun Dashboard.forEachCardConfig(visit: (JsonObject) -> Unit) {
  fun walk(raw: JsonObject) {
    visit(raw)
    (raw["cards"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::walk) }
    (raw["card"] as? JsonObject)?.let(::walk)
  }
  for (view in views) {
    (view.cards + view.sections.flatMap { it.cards } + listOfNotNull(view.header?.card)).forEach {
      walk(it.raw)
    }
  }
}

private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.entityIds(): List<String> =
  (this["entities"] as? JsonArray).orEmpty().mapNotNull { row ->
    when (row) {
      is JsonPrimitive -> row.contentOrNull
      is JsonObject -> (row["entity"] as? JsonPrimitive)?.contentOrNull
      else -> null
    }
  }
