package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaArcDialData
import ee.schimke.ha.rc.components.HaModeChip
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Card design support for the arc-dial cards (thermostat, humidifier). Their dial already reads
 * named bindings — `<slot>.value_fraction`, `<slot>.target_fraction` and the label attributes — so
 * a template only needs the client to push them ([bindings]), the accent index when the accent
 * follows the state, and the steppers rebuilt locally ([action]). The one structural input is
 * whether the entity has a target at all (target marker, supporting label, steppers).
 *
 * Each card supplies [DialCard.data], the same pure function its `Render` draws, so the pushed
 * values are exactly what a live capture would bake.
 */
internal class DialCard(
  val cardType: String,
  val domain: String,
  val configKeys: Set<String>,
  /** The placeholder entity's state and attributes for a template's [DesignProperties]. */
  val placeholder: (DesignProperties) -> Pair<String, Map<String, String>>,
  val data: (CardConfig, HaSnapshot) -> HaArcDialData,
  /**
   * The dial's structural properties: by default whether it has a target. A light's steppers and
   * accent follow its on/off state instead, a discrete state index the client keys on.
   */
  val structure: (CardConfig, HaSnapshot, HaArcDialData) -> Map<String, Int> = { _, _, dial ->
    mapOf(HAS_TARGET to if (dial.targetFraction != null) 1 else 0)
  },
) {
  fun properties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!configKeys.containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    if (CardDesignSlots.domainOf(entityId) != domain || entityId !in snapshot.states) return null
    val dial = data(card, snapshot)
    return DesignProperties.build {
      for ((name, value) in structure(card, snapshot, dial)) enum(name, value)
    }
  }

  fun template(properties: DesignProperties): DesignTemplateInput? {
    if (properties.values.isEmpty()) return null
    val slot = CardDesignSlots.slotId(domain, 0)
    val (state, attributes) = placeholder(properties)
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("entity", slot)
          },
      )
    val entity = CardDesignSlots.templateEntity(slot, state, attributes)
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(slot to entity)))
  }

  fun bindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val entityId =
      card.raw["entity"]?.jsonPrimitive?.contentOrNull
        ?: return CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(entityId), 0)
    val dial = data(card, snapshot)
    val strings = buildMap {
      put("$slot.attributes.${dial.centerLabelAttribute}", dial.centerLabel)
      val supporting = dial.supportingLabel
      val supportingAttribute = dial.supportingLabelAttribute
      if (supporting != null && supportingAttribute != null) {
        put("$slot.attributes.$supportingAttribute", supporting)
      }
      (dial.modeChip as? HaModeChip.Static)?.let {
        put("$slot.attributes.${it.attribute}", it.initial)
      }
    }
    val floats = buildMap {
      put("$slot.value_fraction", dial.valueFraction.coerceIn(0f, 1f))
      dial.targetFraction?.let { put("$slot.target_fraction", it.coerceIn(0f, 1f)) }
    }
    val ints = buildMap {
      dial.accentChoices?.let {
        put("$slot.accent_index", it.indexOf(dial.accent).coerceAtLeast(0))
      }
    }
    return CardSnapshotBindings(strings, emptyMap(), ints, floats)
  }

  /**
   * A stepper tap: the template's encoded action is matched against the steppers its own template
   * renders, and the same stepper is rebuilt from [card]'s live entity — never the template's
   * value.
   */
  fun action(card: CardConfig, snapshot: HaSnapshot?, templateAction: HaAction): HaAction? {
    snapshot ?: return null
    val properties = properties(card, snapshot) ?: return null
    val input = template(properties) ?: return null
    val placeholderDial = data(input.card, input.snapshot)
    val dial = data(card, snapshot)
    return when (templateAction) {
      placeholderDial.incrementAction -> dial.incrementAction
      placeholderDial.decrementAction -> dial.decrementAction
      placeholderDial.tapAction -> dial.tapAction
      else -> null
    }.takeUnless { it == HaAction.None }
  }

  companion object {
    const val HAS_TARGET = "has_target"
  }
}
