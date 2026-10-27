package ee.schimke.terrazzo.wear.widget

import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HaWearLevel
import ee.schimke.ha.rc.framedDesignProperties
import ee.schimke.ha.rc.framedDesignTemplate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The property that marks a Wear template captured with its entity on. A Wear slot's tile tiers
 * resolve their icon and accent when they are captured rather than binding `<id>.is_on` (a second
 * named boolean in a state-layout sibling blanks the other tier, #224), so a template captured from
 * the off placeholder would show an on light's bulb unlit. Added only when the entity is on, and
 * only to the Wear templates' own keys: the phone's and the design server's keys are unchanged.
 */
internal const val WEAR_ON_PROPERTY: String = "wear_on"

/**
 * The properties that carry a level's classification ([HaWearLevel.of]) into a Wear template: the
 * entity's `device_class` and unit. Not every card's design properties keep them (an entity or
 * gauge card's don't keep the unit), so a Wear template adds them for a card whose container is a
 * level, and its template input puts them back on its entity ([wearDesignTemplate]): the cached
 * template then draws the level its card does. Only the Wear templates' own keys.
 */
internal const val WEAR_LEVEL_CLASS_PROPERTY: String = "wear_level_class"
internal const val WEAR_LEVEL_UNIT_PROPERTY: String = "wear_level_unit"

private val WEAR_PROPERTIES =
  setOf(WEAR_ON_PROPERTY, WEAR_LEVEL_CLASS_PROPERTY, WEAR_LEVEL_UNIT_PROPERTY)

/**
 * [card]'s Wear template properties: its [framedDesignProperties], plus [WEAR_ON_PROPERTY] when the
 * one entity it is about is on, and its level's classification when its container is a level.
 */
internal fun CardConverter.wearDesignProperties(
  card: CardConfig,
  snapshot: HaSnapshot,
): DesignProperties? {
  val base = framedDesignProperties(card, snapshot) ?: return null
  val entityId = (card.raw["entity"] as? JsonPrimitive)?.content?.takeIf { '.' in it }
  val state = entityId?.let { snapshot.states[it] }
  val on =
    entityId != null &&
      state != null &&
      entityId.substringBefore('.') in ON_DOMAINS &&
      HaLiveBindings.isOn(entityId, state.state)
  val level = wearLevel(card, snapshot)
  if (!on && level == null) return base
  return DesignProperties.build {
    base.values.forEach { (name, value) -> string(name, value) }
    if (on) string(WEAR_ON_PROPERTY, "1")
    if (level != null && state != null) {
      string(WEAR_LEVEL_CLASS_PROPERTY, state.attribute("device_class").orEmpty())
      string(WEAR_LEVEL_UNIT_PROPERTY, state.attribute("unit_of_measurement").orEmpty())
    }
  }
}

/**
 * The template input for [properties] from [wearDesignProperties]: with its entity on if marked,
 * and its level's classification on its entity's attributes.
 */
internal fun CardConverter.wearDesignTemplate(properties: DesignProperties): DesignTemplateInput? {
  if (WEAR_PROPERTIES.none { properties[it] != null }) return framedDesignTemplate(properties)
  val own = DesignProperties.build {
    properties.values.forEach { (name, value) -> if (name !in WEAR_PROPERTIES) string(name, value) }
  }
  val input = framedDesignTemplate(own) ?: return null
  val entityId =
    (input.card.raw["entity"] as? JsonPrimitive)?.content?.takeIf { '.' in it } ?: return input
  var entity = input.snapshot.states[entityId] ?: EntityState(entityId, "")
  if (properties[WEAR_ON_PROPERTY] == "1") entity = entity.copy(state = ON)
  val classification =
    listOfNotNull(
      properties[WEAR_LEVEL_CLASS_PROPERTY]
        ?.takeIf { it.isNotEmpty() }
        ?.let { "device_class" to it },
      properties[WEAR_LEVEL_UNIT_PROPERTY]
        ?.takeIf { it.isNotEmpty() }
        ?.let { "unit_of_measurement" to it },
    )
  if (classification.isNotEmpty()) {
    entity =
      entity.copy(
        attributes =
          JsonObject(
            entity.attributes + classification.associate { (k, v) -> k to JsonPrimitive(v) }
          )
      )
  }
  if (entity == input.snapshot.states[entityId]) return input
  return input.copy(
    snapshot = input.snapshot.copy(states = input.snapshot.states + (entityId to entity))
  )
}

private fun EntityState.attribute(name: String): String? =
  (attributes[name] as? JsonPrimitive)?.content

private const val ON = "on"

/** Domains whose on state is literally [ON], so a template captured in it is the real one. */
private val ON_DOMAINS = setOf("light", "switch", "binary_sensor", "input_boolean", "fan")

/**
 * The level [card]'s Wear container shows ([HaWearLevel]): its entity's reading, when the entity is
 * a battery, humidity or percentage sensor in [snapshot]. Decided from the entity's `device_class`
 * and unit, which a template's shape carries, so a template input and the card it stands for agree.
 */
internal fun wearLevel(card: CardConfig?, snapshot: HaSnapshot): HaWearLevel? {
  val entityId = (card?.raw?.get("entity") as? JsonPrimitive)?.content ?: return null
  val state = snapshot.states[entityId] ?: return null
  fun attribute(name: String) = (state.attributes[name] as? JsonPrimitive)?.content
  return HaWearLevel.of(
    entityId,
    attribute("device_class"),
    attribute("unit_of_measurement"),
    HaLiveBindings.numericState(state.state) ?: Float.NaN,
  )
}
