package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaHistoryGraphData
import ee.schimke.ha.rc.components.HaHistoryGraphRow
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaHistoryGraph
import ee.schimke.ha.rc.components.RemoteHaHistoryGraphIdentity
import ee.schimke.ha.rc.components.placeholderTitle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `history-graph` card. Renders one sparkline per entity using the snapshot's `history[entityId]`.
 * Per-row summary text is bound to `<entity>.state`; each sample is bound to
 * `<entity>.numeric.<index>` so a host can push the next window of values without re-encoding the
 * document. The y-axis range is fixed at encode time from the captured samples — values that drift
 * well outside the initial range will clip to the canvas, in which case the host should re-encode.
 */
class HistoryGraphCardConverter : CardConverter {
  override val cardType: String = CardTypes.HISTORY_GRAPH

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val ids = entityIds(card)
    val title = if (card.raw["title"] != null) 24 else 0
    val graphed = ids.count { SeriesDesign.hasData(snapshot, it) }
    // No series has a line: one empty-state message plus a line of names.
    if (ids.isNotEmpty() && graphed == 0) return title + 16 + 44 + 20
    // ~52 dp per graphed row (label + sparkline + spacing), ~22 dp per empty row (label only),
    // + range label + padding.
    return title + 16 + 52 * graphed.coerceAtLeast(1) + 22 * (ids.size - graphed) + 20
  }

  /**
   * A history graph renders from a card design: per row, the entity's [EntityDesign] properties
   * (accent) and whether it has a line ([SeriesDesign]); plus the row count and whether there is a
   * title. Whether every row has no line (the card-level empty state) follows from those. Names
   * (`<slot>.name`, a row's `name:` else its friendly name), summaries (`<slot>.summary`), samples,
   * ranges, the title, the range label (`card.range`) and the empty-state message (`card.empty`)
   * are bindings.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val ids = entityIds(card)
    if (ids.isEmpty() || ids.size != (card.raw["entities"] as? JsonArray)?.size) return null
    var ok = true
    val properties = DesignProperties.build {
      enum(HAS_TITLE, if (card.raw["title"] != null) 1 else 0)
      enum(EntityDesign.COUNT, ids.size)
      ids.forEachIndexed { i, id ->
        with(EntityDesign) { if (!entity("e$i.", id, null, snapshot)) ok = false }
        enum("e$i.${SeriesDesign.HAS_DATA}", if (SeriesDesign.hasData(snapshot, id)) 1 else 0)
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
          },
      )
    val history =
      entities.withIndex().associate { (i, e) ->
        e.entityId to
          SeriesDesign.placeholderHistory(properties["e$i.${SeriesDesign.HAS_DATA}"] == "1")
      }
    return DesignTemplateInput(
      card,
      HaSnapshot(states = entities.associateBy { it.entityId }, history = history),
    )
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val ids = entityIds(card)
    val strings = buildMap {
      put("card.range", historyRangeLabel(card))
      put("card.empty", historyEmptyLabel(card))
      val names = historyRowNames(card, snapshot)
      ids.forEachIndexed { i, id ->
        val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(id), i)
        put("$slot.summary", summarise(snapshot.history[id].orEmpty()))
        put("$slot.name", names[i])
      }
    }
    return CardSnapshotBindings(
      strings,
      emptyMap(),
      emptyMap(),
      SeriesDesign.slotBindings(ids, snapshot),
    )
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content
    val ids = entityIds(card)

    val names = historyRowNames(card, snapshot)
    val rows = ids.mapIndexed { i, id ->
      val entity = snapshot.states[id]
      val name = names[i]
      val history = snapshot.history[id].orEmpty()
      val numeric = history.mapNotNull { it.state.toFloatOrNull() }
      HaHistoryGraphRow(
        entityId = id,
        name = name,
        // Its own binding, not `<id>.state`: the summary describes the history, not the state.
        summary = LiveValues.named(id, "summary", summarise(history)),
        accent = HaStateColor.activeFor(entity),
        points = numeric,
      )
    }

    val data =
      HaHistoryGraphData(
        title = title,
        rangeLabel = historyRangeLabel(card),
        rows = rows,
        emptyLabel = historyEmptyLabel(card),
      )

    when (LocalCardSizeMode.current) {
      // Dashboard: the full per-row graph (HA reference).
      CardSizeMode.Wrap -> RemoteHaHistoryGraph(data, modifier = modifier)
      // Launcher / Wear: a single width gate (alpha010 collapses
      // multi-rung ladders to tier 0 — #224, see GaugeCardConverter).
      // Narrow → identity tier (first spark + latest value); wider →
      // the full graph, which fills the larger cell by drawing every
      // series. Width, not height: every Fixed-mode surface pins
      // width and lets height follow (see EntitiesCardConverter).
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(BULK_IDENTITY_THRESHOLD_DP),
          modifier = modifier,
          axis = BreakpointAxis.Width,
        ) { tier ->
          if (tier == 0) {
            RemoteHaHistoryGraphIdentity(data, RemoteModifier.fillMaxWidth())
          } else {
            RemoteHaHistoryGraph(data, RemoteModifier.fillMaxWidth())
          }
        }
    }
  }
}

private fun summarise(points: List<HistoryPoint>): String {
  if (points.isEmpty()) return "no data"
  val numeric = points.mapNotNull { it.state.toDoubleOrNull() }
  if (numeric.isEmpty()) return "${points.size} samples"
  val min = numeric.min()
  val max = numeric.max()
  val fmt: (Double) -> String = { d ->
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
  }
  return "${fmt(min)} – ${fmt(max)} (${points.size})"
}

private fun entityIds(card: CardConfig): List<String> = entityRows(card).map { it.first }

/** Each `entities:` row as its entity id and its own `name:`, when it sets a plain string one. */
private fun entityRows(card: CardConfig): List<Pair<String, String?>> {
  val arr: JsonArray = card.raw["entities"]?.jsonArray ?: return emptyList()
  return arr.mapNotNull { el ->
    when (el) {
      is JsonPrimitive -> el.content to null
      is JsonObject ->
        el["entity"]?.jsonPrimitive?.content?.let { id ->
          id to (el["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
      else -> null
    }
  }
}

/**
 * Each row's display name, in row order. As in Home Assistant's history-graph card, a row's own
 * `name:` wins; otherwise the entity's `friendly_name`, else its id. Nothing is stripped from
 * either.
 */
internal fun historyRowNames(card: CardConfig, snapshot: HaSnapshot): List<String> =
  entityRows(card).map { (id, name) ->
    name
      ?: snapshot.states[id]?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
      ?: id
  }

private val TEMPLATE_CONFIG_KEYS = setOf("type", "title", "entities", "hours_to_show")
private const val HAS_TITLE = "has_title"
private const val DEFAULT_HOURS = 24.0

private fun hoursToShow(card: CardConfig): Double =
  card.raw["hours_to_show"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 }
    ?: DEFAULT_HOURS

/** The card's subtitle: "Last 24 hours", "Last 7 days", "Last 2 weeks". */
internal fun historyRangeLabel(card: CardConfig): String =
  historyWindow(hoursToShow(card)).replaceFirstChar { it.uppercase() }

/** The card-level empty state: "No history in the last 2 days". */
internal fun historyEmptyLabel(card: CardConfig): String =
  "No history in the ${historyWindow(hoursToShow(card))}"

/**
 * `hours_to_show` as words, lower case: "last 6 hours", "last 7 days", "last 2 weeks".
 *
 * Under 48 hours reads in hours ("last 24 hours", "last 36 hours"); whole days from 2 up read in
 * days ("last 2 days", "last 7 days", "last 30 days") unless they are 2 or more whole weeks ("last
 * 2 weeks"); anything else stays in hours ("last 50 hours"), and under an hour in minutes.
 */
internal fun historyWindow(hours: Double): String {
  fun plural(n: Long, unit: String) = if (n == 1L) "last $unit" else "last $n ${unit}s"
  val wholeHours = hours.toLong()
  return when {
    hours < 1 -> plural(Math.round(hours * 60).coerceAtLeast(1), "minute")
    hours != wholeHours.toDouble() -> "last ${"%.1f".format(java.util.Locale.ROOT, hours)} hours"
    wholeHours < 48 || wholeHours % 24 != 0L -> plural(wholeHours, "hour")
    (wholeHours / 24) % 7 == 0L && wholeHours / 24 >= 14 -> plural(wholeHours / 168, "week")
    else -> plural(wholeHours / 24, "day")
  }
}
