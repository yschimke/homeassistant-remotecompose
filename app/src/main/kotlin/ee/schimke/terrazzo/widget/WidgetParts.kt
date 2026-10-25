package ee.schimke.terrazzo.widget

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.dashboard.historyEntityIds
import ee.schimke.terrazzo.dashboard.historyTitle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The cards a long-pressed container holds, each installable as a widget of its own. A stack or
 * grid is one dashboard card, so a long-press on any tile in it targets the whole container; on a
 * 1x1 launcher slot that only has room for its first tile. Empty for any other card, or a container
 * with fewer than two children (nothing to choose between).
 */
internal fun CardConfig.widgetParts(): List<CardConfig> {
  if (type !in CONTAINER_TYPES) return emptyList()
  val children =
    (raw["cards"] as? JsonArray).orEmpty().mapNotNull { child ->
      val obj = child as? JsonObject ?: return@mapNotNull null
      val childType = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      CardConfig(type = childType, raw = obj)
    }
  return children.takeIf { it.size > 1 }.orEmpty()
}

/** A chip label for [card]: its own name, else its entity's friendly name, else its title. */
internal fun widgetPartLabel(card: CardConfig, snapshot: HaSnapshot): String =
  card.raw["name"]?.jsonPrimitive?.contentOrNull
    ?: card.historyEntityIds().firstOrNull()?.let { id ->
      snapshot.states[id]?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
    }
    ?: card.historyTitle()

private val CONTAINER_TYPES =
  setOf(CardTypes.HORIZONTAL_STACK, CardTypes.VERTICAL_STACK, CardTypes.GRID)
