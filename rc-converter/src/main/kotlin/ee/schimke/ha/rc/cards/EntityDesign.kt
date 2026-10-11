package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.placeholderTitle
import ee.schimke.ha.rc.components.titleSizeBucket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Generation properties shared by the entity cards that render from card designs (tile, entity,
 * button, glance, entities): what their converters derive **structure** from for one bound entity.
 *
 * * `domain` — icon family and toggle chrome.
 * * `device_class` — icon and colour, for the domains where it changes them.
 * * `icon` — the entity's `icon` attribute.
 * * `card_icon` — an `icon:` the card config sets for that entity.
 *
 * Everything else the card shows (name, state, on/off) is a named binding. Only [DOMAINS] qualify:
 * their icon and colour come from these properties alone, so a template captured in one state
 * renders every other state through its bindings. A lock's icon or a climate entity's colour
 * follows the state itself, which a template can't, so those cards keep the converter.
 *
 * Multi-entity cards prefix each entity's properties with `e<N>.`, its slot index.
 */
internal object EntityDesign {
  /** Domains whose cards render from card designs. */
  val DOMAINS: List<String> = listOf("light", "switch", "sensor", "binary_sensor")

  /** Domains whose `device_class` changes the icon or colour. */
  private val DEVICE_CLASS_DOMAINS = setOf("sensor", "binary_sensor")

  const val DOMAIN = "domain"
  const val DEVICE_CLASS = "device_class"
  const val ICON = "icon"
  const val CARD_ICON = "card_icon"
  const val COUNT = "count"
  const val TITLE_SIZE = "title_size"

  /**
   * Config keys an `entities:` row may carry for the card to be templated. `tap_action` isn't
   * structural: playback runs the row's own action for a tap on its slot.
   */
  private val ROW_KEYS = setOf("entity", "name", "icon", "tap_action")

  /** Placeholder state: every templated domain models `off`, so toggle chrome is captured. */
  private const val PLACEHOLDER_STATE = "off"

  /**
   * Adds [entityId]'s properties under [prefix]; false when the entity can't be templated (a domain
   * outside [DOMAINS], or not in [snapshot] yet — the converter renders that).
   */
  fun DesignProperties.Builder.entity(
    prefix: String,
    entityId: String?,
    cardIcon: String?,
    snapshot: HaSnapshot,
  ): Boolean {
    if (entityId == null) return false
    val domain = CardDesignSlots.domainOf(entityId)
    if (domain !in DOMAINS) return false
    val state = snapshot.states[entityId] ?: return false
    string(prefix + DOMAIN, domain)
    if (domain in DEVICE_CLASS_DOMAINS) {
      string(prefix + DEVICE_CLASS, state.attr(DEVICE_CLASS))
    }
    string(prefix + ICON, state.attr(ICON)?.trim()?.lowercase())
    string(prefix + CARD_ICON, cardIcon?.trim())
    return true
  }

  /** The slot entity for the properties under [prefix], at slot [index]; null without a domain. */
  fun templateEntity(properties: DesignProperties, prefix: String, index: Int): EntityState? {
    val domain = properties[prefix + DOMAIN] ?: return null
    return CardDesignSlots.templateEntity(
      CardDesignSlots.slotId(domain, index),
      placeholderState = PLACEHOLDER_STATE,
      attributes =
        buildMap {
          properties[prefix + DEVICE_CLASS]?.let { put(DEVICE_CLASS, it) }
          properties[prefix + ICON]?.let { put(ICON, it) }
        },
    )
  }

  /**
   * Properties for a single-entity card (`entity:` plus optional `name:` / `icon:`), or null when
   * it carries any config key outside [configKeys] or its entity can't be templated. [extra] adds
   * card-specific structural properties.
   */
  fun single(
    card: CardConfig,
    snapshot: HaSnapshot,
    configKeys: Set<String>,
    extra: DesignProperties.Builder.() -> Unit = {},
  ): DesignProperties? {
    if (!configKeys.containsAll(card.raw.keys)) return null
    var ok = false
    val properties = DesignProperties.build {
      ok = entity("", card.raw.string("entity"), card.raw.string("icon"), snapshot)
      extra()
    }
    return properties.takeIf { ok }
  }

  /** The template input for a [single] card's [properties]; [config] adds card-specific keys. */
  fun singleTemplate(
    cardType: String,
    properties: DesignProperties,
    config: JsonObjectBuilder.() -> Unit = {},
  ): DesignTemplateInput? {
    val entity = templateEntity(properties, "", 0) ?: return null
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("entity", entity.entityId)
            properties[CARD_ICON]?.let { put("icon", it) }
            config()
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(entity.entityId to entity)))
  }

  /**
   * Properties for a list card (`title:` plus `entities:` rows), or null when it carries a config
   * key outside [configKeys], a row that isn't a plain entity row, or an entity that can't be
   * templated. The title's length picks its font size, so its size bucket is a property too.
   */
  fun list(card: CardConfig, snapshot: HaSnapshot, configKeys: Set<String>): DesignProperties? {
    if (!configKeys.containsAll(card.raw.keys)) return null
    val rows = card.raw["entities"] as? JsonArray ?: return null
    if (rows.isEmpty()) return null
    var ok = true
    val properties = DesignProperties.build {
      card.raw.string("title")?.let { enum(TITLE_SIZE, titleSizeBucket(it)) }
      enum(COUNT, rows.size)
      rows.forEachIndexed { i, row ->
        val (entityId, icon) =
          when (row) {
            is JsonPrimitive -> row.contentOrNull to null
            is JsonObject ->
              if (ROW_KEYS.containsAll(row.keys)) row.string("entity") to row.string("icon")
              else null to null
            else -> null to null
          }
        if (ok && !entity("e$i.", entityId, icon, snapshot)) ok = false
      }
    }
    return properties.takeIf { ok }
  }

  /** The template input for a [list] card's [properties]. */
  fun listTemplate(cardType: String, properties: DesignProperties): DesignTemplateInput? {
    val count = properties[COUNT]?.toIntOrNull() ?: return null
    val entities = (0 until count).map { i -> templateEntity(properties, "e$i.", i) ?: return null }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            properties[TITLE_SIZE]?.toIntOrNull()?.let { put("title", placeholderTitle(it)) }
            putJsonArray("entities") {
              entities.forEachIndexed { i, entity ->
                addJsonObject {
                  put("entity", entity.entityId)
                  properties["e$i.$CARD_ICON"]?.let { put("icon", it) }
                }
              }
            }
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = entities.associateBy { it.entityId }))
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun EntityState.attr(key: String): String? = attributes[key]?.jsonPrimitive?.contentOrNull
}
