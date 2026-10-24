package ee.schimke.terrazzo.wear.widget

import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.framedDesignProperties
import ee.schimke.ha.rc.framedDesignTemplate
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
 * [card]'s Wear template properties: its [framedDesignProperties], plus [WEAR_ON_PROPERTY] when the
 * one entity it is about is on.
 */
internal fun CardConverter.wearDesignProperties(
  card: CardConfig,
  snapshot: HaSnapshot,
): DesignProperties? {
  val base = framedDesignProperties(card, snapshot) ?: return null
  val entityId = (card.raw["entity"] as? JsonPrimitive)?.content?.takeIf { '.' in it }
  val state = entityId?.let { snapshot.states[it]?.state } ?: return base
  if (entityId.substringBefore('.') !in ON_DOMAINS || !HaLiveBindings.isOn(entityId, state))
    return base
  return DesignProperties.build {
    base.values.forEach { (name, value) -> string(name, value) }
    string(WEAR_ON_PROPERTY, "1")
  }
}

/**
 * The template input for [properties] from [wearDesignProperties], with its entity on if marked.
 */
internal fun CardConverter.wearDesignTemplate(properties: DesignProperties): DesignTemplateInput? {
  if (properties[WEAR_ON_PROPERTY] != "1") return framedDesignTemplate(properties)
  val own = DesignProperties.build {
    properties.values.forEach { (name, value) -> if (name != WEAR_ON_PROPERTY) string(name, value) }
  }
  val input = framedDesignTemplate(own) ?: return null
  val entityId =
    (input.card.raw["entity"] as? JsonPrimitive)?.content?.takeIf { '.' in it } ?: return input
  val on = (input.snapshot.states[entityId] ?: EntityState(entityId, ON)).copy(state = ON)
  return input.copy(
    snapshot = input.snapshot.copy(states = input.snapshot.states + (entityId to on))
  )
}

private const val ON = "on"

/** Domains whose on state is literally [ON], so a template captured in it is the real one. */
private val ON_DOMAINS = setOf("light", "switch", "binary_sensor", "input_boolean", "fan")
