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
    val rows = entityIds(card).size.coerceAtLeast(1)
    val title = if (card.raw["title"] != null) 24 else 0
    // ~52 dp per row (label + sparkline + spacing) + range label + padding.
    return title + 16 + 52 * rows + 20
  }

  /**
   * A history graph renders from a card design: per row, the entity's [EntityDesign] properties
   * (accent) and whether it has a line ([SeriesDesign]); plus the row count and whether there is a
   * title. Names, summaries (`<slot>.summary`), samples, ranges, the title and the range label are
   * bindings.
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
      put("card.range", rangeLabel(card))
      ids.forEachIndexed { i, id ->
        val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(id), i)
        put("$slot.summary", summarise(snapshot.history[id].orEmpty()))
        // Rows name their entity by friendly name only, never a per-row `name:`.
        put(
          "$slot.name",
          snapshot.states[id]?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id,
        )
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

    val rows = ids.map { id ->
      val entity = snapshot.states[id]
      val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id
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

    val data = HaHistoryGraphData(title = title, rangeLabel = rangeLabel(card), rows = rows)

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

private val TEMPLATE_CONFIG_KEYS = setOf("type", "title", "entities", "hours_to_show")
private const val HAS_TITLE = "has_title"

private fun rangeLabel(card: CardConfig): String =
  "Last ${card.raw["hours_to_show"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24}h"
