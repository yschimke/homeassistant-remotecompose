package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.StatisticPoint
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaHistoryGraphRow
import ee.schimke.ha.rc.components.HaStatisticsGraphData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaStatisticsGraph
import ee.schimke.ha.rc.components.barRange
import ee.schimke.ha.rc.components.placeholderTitle
import ee.schimke.ha.rc.components.stackMax
import ee.schimke.ha.rc.components.stackValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `statistics-graph` card — mirrors history-graph but reads pre-aggregated values from
 * `HaSnapshot.statistics`.
 *
 * Supports HA's `stat_types` fan-out: each declared stat (`mean`, `min`, `max`, `sum`, `state`)
 * becomes its own row per entity, picking the matching field on [StatisticPoint]. `chart_type: bar`
 * and the `stack: true` modifier are forwarded to the renderer; absent `stat_types` defaults to
 * `[mean]`.
 */
class StatisticsGraphCardConverter : CardConverter {
  override val cardType: String = CardTypes.STATISTICS_GRAPH

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val rows = (entityIds(card).size * statTypes(card).size).coerceAtLeast(1)
    val title = if (card.raw["title"] != null) 24 else 0
    return title + 16 + 52 * rows + 20
  }

  /**
   * A line-chart statistics graph with one statistic per entity renders from a card design, as a
   * history graph does: per row, the entity's [EntityDesign] properties and whether it has a line;
   * samples, ranges, summaries (`<slot>.summary`), names, the title and the period are bindings.
   * Bar charts (one bar per bucket) and several statistics per entity keep the converter.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val chart = card.raw["chart_type"]?.jsonPrimitive?.content ?: "line"
    if (chart != "line" && chart != "bar") return null
    if (statTypes(card).size != 1) return null
    val ids = entityIds(card)
    if (ids.isEmpty() || ids.size != (card.raw["entities"] as? JsonArray)?.size) return null
    val statType = statTypes(card).single()
    var ok = true
    val properties = DesignProperties.build {
      enum(HAS_TITLE, if (card.raw["title"] != null) 1 else 0)
      enum(EntityDesign.COUNT, ids.size)
      // A bar chart draws one bar per bucket, so each row's bucket count is structure.
      if (chart == "bar") {
        string(CHART, chart)
        enum(STACKED, if (isStacked(card)) 1 else 0)
      }
      ids.forEachIndexed { i, id ->
        with(EntityDesign) { if (!entity("e$i.", id, null, snapshot)) ok = false }
        val points = statisticPoints(snapshot, id, statType)
        if (chart == "bar") enum("e$i.$BARS", points.size)
        else enum("e$i.${SeriesDesign.HAS_DATA}", if (points.size >= 2) 1 else 0)
      }
    }
    return properties.takeIf { ok }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val count = properties[EntityDesign.COUNT]?.toIntOrNull() ?: return null
    val entities =
      (0 until count).map { i -> EntityDesign.templateEntity(properties, "e$i.", i) ?: return null }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            if (properties[HAS_TITLE] == "1") put("title", placeholderTitle(0))
            putJsonArray("entities") { entities.forEach { add(it.entityId) } }
            properties[CHART]?.let { put("chart_type", it) }
            if (properties[STACKED] == "1") put("stack", true)
          },
      )
    val statistics =
      entities.withIndex().associate { (i, e) ->
        val count =
          properties["e$i.$BARS"]?.toIntOrNull()
            ?: if (properties["e$i.${SeriesDesign.HAS_DATA}"] == "1") 2 else 0
        e.entityId to
          List(count) { k ->
            StatisticPoint(
              kotlin.time.Instant.fromEpochMilliseconds(k * 3_600_000L),
              mean = k + 1.0,
            )
          }
      }
    return DesignTemplateInput(
      card,
      HaSnapshot(states = entities.associateBy { it.entityId }, statistics = statistics),
    )
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val ids = entityIds(card)
    val statType = statTypes(card).first()
    val strings = buildMap {
      put("card.range", periodLabel(card))
      ids.forEachIndexed { i, id ->
        val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(id), i)
        put("$slot.summary", summariseStatistics(snapshot.statistics[id].orEmpty(), statType))
        put(
          "$slot.name",
          snapshot.states[id]?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id,
        )
      }
    }
    val series = ids.map { statisticPoints(snapshot, it, statType) }
    val floats =
      if ((card.raw["chart_type"]?.jsonPrimitive?.content ?: "line") != "bar") {
        SeriesDesign.slotBindings(ids, snapshot) { statisticPoints(snapshot, it, statType) }
      } else {
        buildMap {
          val stacked = isStacked(card)
          if (stacked) put("card.stack_max", stackMax(series))
          val buckets = series.maxOfOrNull { it.size } ?: 0
          ids.forEachIndexed { i, id ->
            val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(id), i)
            val points = series[i]
            if (stacked) {
              repeat(buckets) { k -> put("$slot.stack.$k", stackValue(points, k)) }
            } else {
              val range = barRange(points)
              put("$slot.bar_base", range.min)
              put("$slot.bar_span", range.span)
              points.forEachIndexed { k, v -> put("$slot.bar.$k", v) }
            }
          }
        }
      }
    return CardSnapshotBindings(strings, emptyMap(), emptyMap(), floats)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content
    val ids = entityIds(card)
    val stats = statTypes(card)
    val chartType = card.raw["chart_type"]?.jsonPrimitive?.content ?: "line"
    val stacked = isStacked(card)

    val rows = ids.flatMap { id ->
      val entity = snapshot.states[id]
      val baseName = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id
      val statistics = snapshot.statistics[id].orEmpty()
      stats.map { statType ->
        val numeric = statisticPoints(snapshot, id, statType)
        val rowName = if (stats.size > 1) "$baseName · $statType" else baseName
        HaHistoryGraphRow(
          entityId = id,
          name = rowName,
          // Its own binding, not `<id>.state`: the summary describes the statistics.
          summary = LiveValues.named(id, "summary", summariseStatistics(statistics, statType)),
          accent = HaStateColor.activeFor(entity),
          points = numeric,
        )
      }
    }

    RemoteHaStatisticsGraph(
      HaStatisticsGraphData(
        title = title,
        rangeLabel = periodLabel(card),
        rows = rows,
        chartType = chartType,
        stacked = stacked,
      ),
      modifier = modifier,
    )
  }
}

private fun selectStat(point: StatisticPoint, statType: String): Double? =
  when (statType) {
    "mean" -> point.mean
    "min" -> point.min
    "max" -> point.max
    "sum" -> point.sum
    "state" -> point.state
    else -> point.mean
  }

private fun summariseStatistics(points: List<StatisticPoint>, statType: String): String {
  if (points.isEmpty()) return "no data"
  val numeric = points.mapNotNull { selectStat(it, statType) }
  if (numeric.isEmpty()) return "${points.size} buckets"
  val min = numeric.min()
  val max = numeric.max()
  val fmt: (Double) -> String = { d ->
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
  }
  return "${fmt(min)} – ${fmt(max)} (${points.size})"
}

private fun statTypes(card: CardConfig): List<String> {
  val arr = card.raw["stat_types"]?.jsonArray
  val parsed = arr?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
  return parsed.ifEmpty { listOf("mean") }
}

private fun entityIds(card: CardConfig): List<String> {
  val arr: JsonArray = card.raw["entities"]?.jsonArray ?: return emptyList()
  return arr.mapNotNull { el ->
    when (el) {
      is JsonPrimitive -> el.content
      is JsonObject -> el["entity"]?.jsonPrimitive?.content
      else -> null
    }
  }
}

private val TEMPLATE_CONFIG_KEYS =
  setOf("type", "title", "entities", "period", "stat_types", "chart_type", "days_to_show", "stack")
private const val CHART = "chart"
private const val STACKED = "stacked"
private const val BARS = "bars"

private fun isStacked(card: CardConfig): Boolean =
  card.raw["stack"]?.jsonPrimitive?.booleanOrNull == true &&
    (card.raw["chart_type"]?.jsonPrimitive?.content ?: "line") == "bar"

private const val HAS_TITLE = "has_title"

private fun periodLabel(card: CardConfig): String =
  "Period: ${card.raw["period"]?.jsonPrimitive?.content ?: "hour"}"

private fun statisticPoints(snapshot: HaSnapshot, entityId: String, statType: String): List<Float> =
  snapshot.statistics[entityId].orEmpty().mapNotNull { selectStat(it, statType)?.toFloat() }
