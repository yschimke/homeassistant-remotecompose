package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `entity-filter` card — filters the configured entity list against a `state_filter:` (each filter
 * is either a string or `{ value: ..., operator: ==|!=|<|>|>=|<= }`) and forwards the kept entities
 * to a sub-card (defaulting to `glance`). Uses the existing GlanceCardConverter /
 * EntitiesCardConverter / TileCardConverter machinery rather than rendering its own visual.
 */
class EntityFilterCardConverter : CardConverter {
  override val cardType: String = CardTypes.ENTITY_FILTER

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 150

  /**
   * An entity filter renders as the card it builds over the matching entities: the client filters
   * on live data, and the result keys, binds and taps as that card does.
   */
  override fun designDelegate(card: CardConfig, snapshot: HaSnapshot): CardConfig =
    filteredCard(card, snapshot)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val sub = filteredCard(card, snapshot)
    val converter: CardConverter =
      when (sub.type) {
        "entities" -> EntitiesCardConverter()
        "tile" -> TileCardConverter()
        else -> GlanceCardConverter()
      }
    converter.Render(sub, snapshot, modifier)
  }
}

/**
 * The entities an `entity-filter` card shows. Without a `state_filter`, that is the active ones, by
 * the same [HaLiveBindings.isOn] rule tiles use for their accent, so a locked lock or a heating
 * thermostat is listed just as a light that is on is.
 */
internal fun filteredEntities(card: CardConfig, snapshot: HaSnapshot): List<String> {
  val arr = card.raw["entities"] as? JsonArray ?: return emptyList()
  val filters = (card.raw["state_filter"] as? JsonArray)?.toList() ?: emptyList()
  return arr
    .mapNotNull { el ->
      when (el) {
        is JsonPrimitive -> el.content
        is JsonObject -> el["entity"]?.jsonPrimitive?.content
        else -> null
      }
    }
    .filter { id ->
      val state = snapshot.states[id]?.state ?: return@filter false
      if (filters.isEmpty()) HaLiveBindings.isOn(id, state) else filters.any { matches(state, it) }
    }
}

private fun matches(state: String, filter: kotlinx.serialization.json.JsonElement): Boolean {
  return when (filter) {
    is JsonPrimitive -> filter.content == state
    is JsonObject -> {
      val value = filter["value"]?.jsonPrimitive?.content ?: return false
      val op = filter["operator"]?.jsonPrimitive?.content ?: "=="
      when (op) {
        "==" -> state == value
        "!=" -> state != value
        ">",
        "<",
        ">=",
        "<=" -> {
          val a = state.toDoubleOrNull() ?: return false
          val b = value.toDoubleOrNull() ?: return false
          when (op) {
            ">" -> a > b
            "<" -> a < b
            ">=" -> a >= b
            "<=" -> a <= b
            else -> false
          }
        }
        "regex" -> Regex(value).matches(state)
        else -> false
      }
    }
    else -> false
  }
}

/**
 * The card an entity filter shows: its `card:` (a glance by default) over the matching entities.
 */
private fun filteredCard(card: CardConfig, snapshot: HaSnapshot): CardConfig {
  val ids = filteredEntities(card, snapshot)
  val cardCfg = card.raw["card"] as? JsonObject
  val requested = cardCfg?.get("type")?.jsonPrimitive?.content ?: "glance"
  // Only these render the filtered rows; anything else falls back to a glance, as Render does.
  val targetType = if (requested in setOf("glance", "entities", "tile")) requested else "glance"
  val title = cardCfg?.get("title")?.jsonPrimitive?.content ?: "Entity filter"
  val mergedRaw = buildJsonObject {
    put("type", JsonPrimitive(targetType))
    put("title", JsonPrimitive(title))
    put("entities", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    cardCfg?.entries?.forEach { (k, v) ->
      if (k != "type" && k != "title" && k != "entities") put(k, v)
    }
  }
  return CardConfig(type = targetType, raw = mergedRaw)
}
