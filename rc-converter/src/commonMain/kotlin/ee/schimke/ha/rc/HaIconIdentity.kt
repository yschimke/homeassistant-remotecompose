package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether a card's icon tells it apart on its own: the icon exception of
 * `docs/architecture/card-information-hierarchy.md`. Only at a 1×1 launcher widget, where the name
 * has to compete with the value for the only line, may an **evocative** icon carry the identity
 * alone; a **generic** one keeps the name under the chip.
 *
 * Evocative: a domain with one obvious instance per room or home drawn with a distinctive pictogram
 * ([EvocativeDomains], and a cover only with a garage, gate or door device class), or any card with
 * a configured `icon:` (someone picked it to mean this entity). Everything else is generic,
 * including a domain this list does not know: the name drops only when the icon is known to stand
 * in for it.
 */
object HaIconIdentity {

  /** Domains whose pictogram identifies the entity in a home on its own. */
  val EvocativeDomains: Set<String> =
    setOf(
      "lock",
      "climate",
      "alarm_control_panel",
      "vacuum",
      "media_player",
      "camera",
      "water_heater",
    )

  /** `cover` device classes drawn as a distinctive door: a garage, a gate, a door. */
  val EvocativeCoverClasses: Set<String> = setOf("garage", "gate", "door")

  /**
   * True when the icon may stand in for the name: [domain] (`lock`, from `lock.front_door`), the
   * entity's [deviceClass], and the card's [configuredIcon] (its `icon:`, blank counts as unset).
   */
  fun isEvocative(domain: String?, deviceClass: String?, configuredIcon: String?): Boolean {
    if (!configuredIcon.isNullOrBlank()) return true
    if (domain in EvocativeDomains) return true
    return domain == "cover" && deviceClass in EvocativeCoverClasses
  }

  /** [isEvocative] for [card]'s `entity` and `icon:`, reading the device class off [entity]. */
  fun isEvocative(card: CardConfig, entity: EntityState?): Boolean {
    val entityId = (card.raw["entity"] as? JsonPrimitive)?.contentOrNull ?: entity?.entityId
    return isEvocative(
      domain = entityId?.substringBefore('.', missingDelimiterValue = "")?.ifEmpty { null },
      deviceClass = (entity?.attributes?.get("device_class") as? JsonPrimitive)?.contentOrNull,
      configuredIcon = (card.raw["icon"] as? JsonPrimitive)?.contentOrNull,
    )
  }

  /** The 1×1 icon chip keeps the name under it: the inverse of [isEvocative]. */
  fun chipKeepsName(card: CardConfig, entity: EntityState?): Boolean = !isEvocative(card, entity)

  /**
   * What a converter's 1×1 chip draws: [chipKeepsName], except inside a stack or grid
   * ([LocalInCardContainer]), whose cells are the multi-entity card's and drop per-entity names.
   */
  @Composable
  fun chipShowsName(card: CardConfig, entity: EntityState?): Boolean =
    !LocalInCardContainer.current && chipKeepsName(card, entity)
}
