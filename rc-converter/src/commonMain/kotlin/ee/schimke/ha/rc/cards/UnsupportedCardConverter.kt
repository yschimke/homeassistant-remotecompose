package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaUnsupportedData
import ee.schimke.ha.rc.components.RemoteHaUnsupported
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fallback for card types we haven't implemented (custom/HACS cards, iframe, …).
 *
 * When the card's config names entities, it renders as a generic `entities` card over them, so a
 * `custom:mushroom-light-card` still shows its light's name and state and toggles it. With no
 * entities to show, it renders a visible placeholder so the dashboard still lays out correctly and
 * missing pieces are obvious.
 */
class UnsupportedCardConverter(private val declaredType: String? = null) : CardConverter {
  override val cardType: String = declaredType ?: UNSUPPORTED_CARD_TYPE

  // Icon (24) + 8 gap + "Not yet supported" (14sp) + card type (12sp)
  // + 12 padding top/bottom ≈ 88 dp; round to 96 to give descenders
  // room. The 160 dp default left ~70 dp of empty space below the
  // placeholder content in every dashboard slot.
  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int =
    genericCard(card)?.let { ENTITIES.naturalHeightDp(it, snapshot) } ?: 96

  /**
   * A card that names entities renders as the generic entities card over them: it keys, binds and
   * taps as that card does, so it keeps up with live data the same way.
   */
  override fun designDelegate(card: CardConfig, snapshot: HaSnapshot): CardConfig? =
    genericCard(card)

  /**
   * The placeholder renders from a card design: it shows nothing but the card's type, which is
   * already part of the design key, so it has no generation properties of its own.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties =
    DesignProperties.build {}

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput =
    DesignTemplateInput(
      CardConfig(type = cardType, raw = buildJsonObject { put("type", cardType) }),
      HaSnapshot(),
    )

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val generic = genericCard(card)
    if (generic != null) {
      ENTITIES.Render(generic, snapshot, modifier)
    } else {
      val fixed = LocalCardSizeMode.current == CardSizeMode.Fixed
      RemoteHaUnsupported(
        HaUnsupportedData(cardType = card.type),
        // A fixed cell centres the placeholder rather than top-gluing it.
        modifier = if (fixed) modifier.fillMaxSize() else modifier,
        compact = fixed,
      )
    }
  }

  private companion object {
    val ENTITIES = EntitiesCardConverter()
  }
}

/** At most this many rows: a custom card's config can name far more entities than it shows. */
private const val MAX_GENERIC_ROWS = 12

/**
 * The generic `entities` card an unsupported [card] renders as, or null when its config names no
 * entities. Keeps the card's `title` (or `name`) and passes each row's `name`, `icon` and
 * `tap_action` through, so a custom card's own labels and taps survive.
 */
internal fun genericCard(card: CardConfig): CardConfig? {
  if (card.type == CardTypes.IFRAME) return null
  val rows = genericRows(card)
  if (rows.isEmpty()) return null
  // A single-entity card's `name` labels its row (see [rowOf]); otherwise it can title the list.
  val ownsEntity = card.raw["entity"] is JsonPrimitive
  val title =
    (card.raw["title"] as? JsonPrimitive
        ?: (card.raw["name"] as? JsonPrimitive)?.takeIf { !ownsEntity })
      ?.content
  val raw = buildJsonObject {
    put("type", CardTypes.ENTITIES)
    title?.let { put("title", it) }
    put("entities", buildJsonArray { rows.forEach { add(it) } })
  }
  return CardConfig(type = CardTypes.ENTITIES, raw = raw)
}

/**
 * The entity rows [card] names, in config order: exactly the entities [cardEntityIds] finds, which
 * are the ones a host pushes live values for, so every row it renders keeps up with Home Assistant.
 * Each row keeps the `name`, `icon` and `tap_action` of the first object that names its entity (an
 * `entities` row, a nested card, or the card itself).
 */
internal fun genericRows(card: CardConfig): List<JsonElement> {
  val objects = HashMap<String, JsonObject>()

  fun visit(element: JsonElement) {
    when (element) {
      is JsonObject -> {
        val entity = (element["entity"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (entity != null && entity !in objects) objects[entity] = rowOf(element, entity)
        element.values.forEach(::visit)
      }
      is JsonArray -> element.forEach(::visit)
      else -> {}
    }
  }

  visit(card.raw)
  return cardEntityIds(card).take(MAX_GENERIC_ROWS).map { objects[it] ?: JsonPrimitive(it) }
}

/** A row object the entities card understands: its entity plus the row-level fields it reads. */
private fun rowOf(obj: JsonObject, entity: String): JsonObject = buildJsonObject {
  put("entity", entity)
  for (field in listOf("name", "icon", "tap_action")) obj[field]?.let { put(field, it) }
}
