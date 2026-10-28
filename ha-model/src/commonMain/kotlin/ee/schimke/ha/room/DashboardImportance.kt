package ee.schimke.ha.room

import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.Dashboard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a user's dashboards say matters: each entity they show, scored by how they show it, and the
 * areas an `area` card gives a place of its own.
 *
 * A dashboard is the user's own answer to "which of my hundreds of entities do I reach for?", so
 * the room controls rank by it rather than guessing from domains alone. An entity scores more on a
 * card made for controlling it (a tile, a thermostat, a media control) than as a row of an entities
 * card, and more there than on a graph or gauge that only shows it; more on a dashboard's first
 * view than deeper in; and more again for each further card that shows it. A tile's `features` (a
 * volume slider, a target temperature) mark an entity the user controls from the dashboard.
 */
data class DashboardImportance(
  val entities: Map<String, Float>,
  val areas: Map<String, Float>,
) {
  /** [entityId]'s score, 0 when no dashboard shows it. */
  fun of(entityId: String): Float = entities[entityId] ?: 0f

  operator fun plus(other: DashboardImportance): DashboardImportance =
    DashboardImportance(
      entities = merge(entities, other.entities),
      areas = merge(areas, other.areas),
    )

  companion object {
    val Empty = DashboardImportance(emptyMap(), emptyMap())

    /** [Empty] plus each of [dashboards], scored with [dashboardImportance]. */
    fun of(dashboards: Iterable<Dashboard>): DashboardImportance =
      dashboards.fold(Empty) { acc, dashboard -> acc + dashboardImportance(dashboard) }

    private fun merge(a: Map<String, Float>, b: Map<String, Float>): Map<String, Float> {
      if (a.isEmpty()) return b
      if (b.isEmpty()) return a
      return (a.keys + b.keys).associateWith { (a[it] ?: 0f) + (b[it] ?: 0f) }
    }
  }
}

/** Scores [dashboard]'s entities and areas as [DashboardImportance] describes. */
fun dashboardImportance(dashboard: Dashboard): DashboardImportance {
  val entities = LinkedHashMap<String, Float>()
  val areas = LinkedHashMap<String, Float>()
  dashboard.views.forEachIndexed { index, view ->
    val viewWeight = if (index == 0) FIRST_VIEW_WEIGHT else 1f
    val cards = listOfNotNull(view.header?.card) + view.cards + view.sections.flatMap { it.cards }
    cards.forEach { walk(it.raw, viewWeight, entities, areas) }
  }
  return DashboardImportance(entities, areas)
}

private fun walk(
  card: JsonObject,
  viewWeight: Float,
  entities: MutableMap<String, Float>,
  areas: MutableMap<String, Float>,
) {
  val type = card.string("type")
  if (type == CardTypes.AREA) {
    card.string("area")?.let { areas[it] = (areas[it] ?: 0f) + viewWeight }
  }
  val weight = viewWeight * cardWeight(type, card)
  card.string("entity")?.takeIf { '.' in it }?.let { add(entities, it, weight) }
  (card["entities"] as? JsonArray)?.forEach { row ->
    val id =
      when (row) {
        is JsonPrimitive -> row.contentOrNull
        is JsonObject -> row.string("entity")
        else -> null
      }
    // A row is one of many on its card: it counts as a row, whatever the card.
    if (id != null && '.' in id) add(entities, id, viewWeight * ROW_WEIGHT)
  }
  (card["cards"] as? JsonArray)?.forEach {
    (it as? JsonObject)?.let { c -> walk(c, viewWeight, entities, areas) }
  }
  (card["card"] as? JsonObject)?.let { walk(it, viewWeight, entities, areas) }
}

private fun add(entities: MutableMap<String, Float>, id: String, weight: Float) {
  entities[id] = (entities[id] ?: 0f) + weight
}

private fun cardWeight(type: String?, card: JsonObject): Float {
  val base =
    when (type) {
      in CONTROL_CARDS -> CONTROL_WEIGHT
      in DISPLAY_CARDS -> DISPLAY_WEIGHT
      else -> ROW_WEIGHT
    }
  val features = (card["features"] as? JsonArray)?.size ?: 0
  return base + if (features > 0) FEATURE_BONUS else 0f
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Cards made for controlling their entity. */
private val CONTROL_CARDS =
  setOf(
    CardTypes.TILE,
    CardTypes.BUTTON,
    CardTypes.LIGHT,
    CardTypes.THERMOSTAT,
    CardTypes.HUMIDIFIER,
    CardTypes.MEDIA_CONTROL,
    CardTypes.ALARM_PANEL,
    CardTypes.PICTURE_ENTITY,
  )

/** Cards that only show their entity: worth something, but not a control. */
private val DISPLAY_CARDS =
  setOf(
    CardTypes.GAUGE,
    CardTypes.SENSOR,
    CardTypes.HISTORY_GRAPH,
    CardTypes.STATISTICS_GRAPH,
    CardTypes.STATISTIC,
    CardTypes.WEATHER_FORECAST,
  )

private const val CONTROL_WEIGHT = 3f
private const val ROW_WEIGHT = 2f
private const val DISPLAY_WEIGHT = 1f
private const val FEATURE_BONUS = 1f
private const val FIRST_VIEW_WEIGHT = 1.5f
