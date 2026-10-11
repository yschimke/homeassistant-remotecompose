package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Slot mapping for design templates: which entities a card binds, under which slot names, and the
 * structural shape that picks a template.
 *
 * A template is captured against **slot entity ids** (`<domain>.s<N>`, e.g. `light.s0`) instead of
 * real ones, so `LiveValues` bakes `light.s0.state`, `light.s0.is_on`, … The domain stays in the
 * slot id (and in the shape) because converters still derive structure from it — icon family,
 * whether the tile toggles. The client maps each slot back to the card's real entity and pushes
 * values under the slot names ([slotBindings]).
 */
object CardDesignSlots {

  /** Card types the spike can serve as templates. Everything else uses the local converter. */
  val SUPPORTED_TYPES: Set<String> = setOf(CardTypes.TILE)

  /** Domains the spike builds tile templates for. */
  val TILE_DOMAINS: List<String> = listOf("light", "switch", "sensor", "binary_sensor")

  /**
   * Config keys a templated tile may carry. Anything else (custom `icon`, `tap_action`, `color`,
   * features, …) changes structure or actions the spike doesn't model yet, so the card isn't
   * templatable and renders through the local converter.
   */
  private val TILE_KEYS = setOf("type", "entity", "name")

  fun slotId(domain: String, index: Int): String = "$domain.s$index"

  fun domainOf(entityId: String): String = entityId.substringBefore('.', missingDelimiterValue = "")

  /** Real entity ids a card binds, in slot order, or null when the card can't be templated. */
  fun slotEntities(card: CardConfig): List<String>? {
    if (!isTemplatable(card)) return null
    return listOf(card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null)
  }

  /** Whether [card]'s config is one the template path models at all (type, keys, domain). */
  fun isTemplatable(card: CardConfig): Boolean {
    if (card.type !in SUPPORTED_TYPES) return false
    if (!TILE_KEYS.containsAll(card.raw.keys)) return false
    val entity = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return false
    return domainOf(entity) in TILE_DOMAINS
  }

  /**
   * Normalised structural shape for [card], or null when it can't be templated. Holds only what
   * changes the document the converter emits — never a value such as a name or a state:
   * - `domain` — icon family and whether the tile toggles;
   * - `device_class` (sensors and binary sensors) — their icon and accent colour;
   * - `icon` — an `icon` attribute on the entity, which overrides the default icon.
   *
   * Null when the entity isn't in [snapshot] yet: the local converter renders that state.
   */
  fun shapeOf(card: CardConfig, snapshot: HaSnapshot): String? {
    val entity = slotEntities(card)?.single() ?: return null
    val state = snapshot.states[entity] ?: return null
    val domain = domainOf(entity)
    return buildString {
      append("domain=").append(domain)
      if (domain in DEVICE_CLASS_DOMAINS) {
        state.attributes["device_class"]?.jsonPrimitive?.contentOrNull?.let {
          append(";device_class=").append(it)
        }
      }
      state.attributes["icon"]?.jsonPrimitive?.contentOrNull?.let {
        append(";icon=").append(it.trim().lowercase())
      }
    }
  }

  /** Parse a shape from [shapeOf] back into its fields. */
  fun parseShape(shape: String): Map<String, String> =
    shape
      .split(';')
      .mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
      }
      .toMap()

  /** Domains whose `device_class` changes the tile's icon or colour. */
  private val DEVICE_CLASS_DOMAINS = setOf("sensor", "binary_sensor")

  /** Map real entity id → slot entity id for [card]. Empty when the card has no template shape. */
  fun slotMap(card: CardConfig): Map<String, String> =
    slotEntities(card).orEmpty().mapIndexed { i, id -> id to slotId(domainOf(id), i) }.toMap()

  /**
   * The card config a template is captured from: the same shape, with a slot entity id. Used by the
   * template builder.
   */
  fun templateCard(cardType: String, shape: String): CardConfig {
    val domain = requireNotNull(parseShape(shape)["domain"]) { "shape has no domain: $shape" }
    return CardConfig(
      type = cardType,
      raw =
        buildJsonObject {
          put("type", cardType)
          put("entity", slotId(domain, 0))
        },
    )
  }

  /**
   * Placeholder snapshot for template capture: the shape's attributes on the slot entity, and
   * placeholder values that only seed the named bindings' initial values (the client overwrites
   * every one of them). The state is `off`, which every templated domain models, so toggle chrome
   * is captured with its binding (an `unknown` placeholder would make the converter leave it out).
   */
  fun templateSnapshot(shape: String, placeholderState: String = "off"): HaSnapshot {
    val fields = parseShape(shape)
    val domain = requireNotNull(fields["domain"]) { "shape has no domain: $shape" }
    val id = slotId(domain, 0)
    val attributes = buildMap {
      put("friendly_name", JsonPrimitive(NAME_PLACEHOLDER))
      fields["device_class"]?.let { put("device_class", JsonPrimitive(it)) }
      fields["icon"]?.let { put("icon", JsonPrimitive(it)) }
    }
    return HaSnapshot(
      states =
        mapOf(
          id to
            EntityState(
              entityId = id,
              state = placeholderState,
              attributes = JsonObject(attributes),
            )
        )
    )
  }

  /**
   * Every binding a template reads for [card], keyed by slot name. Unlike the entity-keyed
   * `cardSnapshotBindings`, every binding of every slot is always present: a value with no meaning
   * gets a neutral default, so nothing from a previous entity state lingers in the player, and
   * `<slot>.status` says why.
   */
  fun slotBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val strings = LinkedHashMap<String, String>()
    val booleans = LinkedHashMap<String, Boolean>()
    val ints = LinkedHashMap<String, Int>()
    val floats = LinkedHashMap<String, Float>()
    for ((entityId, slot) in slotMap(card)) {
      val state = snapshot.states[entityId]
      val name =
        card.raw["name"]?.jsonPrimitive?.contentOrNull
          ?: state?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
          ?: entityId
      strings["$slot.state"] = formatState(state)
      strings["$slot.name"] = name
      booleans["$slot.is_on"] = state?.let { HaLiveBindings.isOn(entityId, it.state) } ?: false
      ints["$slot.state_int"] = state?.let { HaLiveBindings.stateInt(entityId, it.state) } ?: -1
      ints["$slot.status"] = statusOf(state)
      floats["$slot.numeric_state"] =
        state?.let { HaLiveBindings.numericState(it.state) } ?: Float.NaN
    }
    return CardSnapshotBindings(strings, booleans, ints, floats)
  }

  /** `<slot>.status`: 0 available, 1 unavailable, 2 unknown, 3 entity missing. */
  fun statusOf(state: EntityState?): Int =
    when (state?.state) {
      null -> STATUS_MISSING
      "unavailable" -> STATUS_UNAVAILABLE
      "unknown" -> STATUS_UNKNOWN
      else -> STATUS_AVAILABLE
    }

  const val STATUS_AVAILABLE: Int = 0
  const val STATUS_UNAVAILABLE: Int = 1
  const val STATUS_UNKNOWN: Int = 2
  const val STATUS_MISSING: Int = 3

  /** Placeholder name baked into a template; the `<slot>.name` binding replaces it. */
  const val NAME_PLACEHOLDER: String = "Name"
}
