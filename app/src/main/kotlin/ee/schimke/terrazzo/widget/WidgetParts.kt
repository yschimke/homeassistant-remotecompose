package ee.schimke.terrazzo.widget

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.dashboard.historyEntityIds
import ee.schimke.terrazzo.dashboard.historyTitle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One thing a long-press can put on the home screen, with the chip that offers it. */
internal data class WidgetPart(val card: CardConfig, val label: String)

/**
 * What a long-press on [card] can install, widest first: the whole section it sits in
 * ([sectionCards], as one vertical stack), the card itself, and — when it is a stack or grid — each
 * row, column and single cell inside it, depth first. A stack or grid is one dashboard card, so a
 * long-press on any tile in it targets the container; on a 1x1 launcher slot that only has room for
 * its first tile, so the tile meant has to be offered on its own.
 *
 * Empty when there is nothing to choose between: a lone card outside a section, or the only card in
 * one.
 */
internal fun widgetParts(
  card: CardConfig,
  sectionCards: List<CardConfig>,
  snapshot: HaSnapshot,
): List<WidgetPart> {
  val parts = buildList {
    if (sectionCards.size > 1 && card in sectionCards) {
      add(WidgetPart(sectionCard(sectionCards), "Whole section"))
    }
    fun walk(c: CardConfig) {
      val children = c.childCards()
      add(
        WidgetPart(c, if (children.isEmpty()) widgetPartLabel(c, snapshot) else c.containerLabel())
      )
      children.forEach(::walk)
    }
    walk(card)
  }
  return parts.takeIf { it.size > 1 }.orEmpty()
}

/** A chip label for [card]: its own name, else its entity's friendly name, else its title. */
internal fun widgetPartLabel(card: CardConfig, snapshot: HaSnapshot): String =
  card.raw["name"]?.jsonPrimitive?.contentOrNull
    ?: card.historyEntityIds().firstOrNull()?.let { id ->
      snapshot.states[id]?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
    }
    ?: card.historyTitle()

/** The section's cards as one card: stacked, as a one-column section draws them. */
private fun sectionCard(cards: List<CardConfig>): CardConfig =
  CardConfig(
    type = CardTypes.VERTICAL_STACK,
    raw =
      JsonObject(
        mapOf(
          "type" to JsonPrimitive(CardTypes.VERTICAL_STACK),
          "cards" to JsonArray(cards.map { it.raw }),
        )
      ),
  )

private fun CardConfig.containerLabel(): String {
  val count = childCards().size
  return when (type) {
    CardTypes.HORIZONTAL_STACK -> "Row of $count"
    CardTypes.VERTICAL_STACK -> "Column of $count"
    else -> "Grid of $count"
  }
}

/** A stack's or grid's cards; none for any other card, or a container holding fewer than two. */
private fun CardConfig.childCards(): List<CardConfig> {
  if (type !in CONTAINER_TYPES) return emptyList()
  val children =
    (raw["cards"] as? JsonArray).orEmpty().mapNotNull { child ->
      val obj = child as? JsonObject ?: return@mapNotNull null
      val childType = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      CardConfig(type = childType, raw = obj)
    }
  return children.takeIf { it.size > 1 }.orEmpty()
}

private val CONTAINER_TYPES =
  setOf(CardTypes.HORIZONTAL_STACK, CardTypes.VERTICAL_STACK, CardTypes.GRID)
