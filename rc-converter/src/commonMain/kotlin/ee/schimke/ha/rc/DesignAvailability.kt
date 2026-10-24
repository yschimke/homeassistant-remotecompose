package ee.schimke.ha.rc

import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonObject

/**
 * The design property that marks a card whose entity is unavailable: its template is captured with
 * the entity unavailable, so it carries the uber theme's unavailable frame ([RenderChild]). Added
 * only when the entity is unavailable, so every other design key is unchanged.
 */
const val UNAVAILABLE_DESIGN_PROPERTY: String = "unavailable"

/**
 * This converter's [CardConverter.designProperties] for [card], plus [UNAVAILABLE_DESIGN_PROPERTY]
 * when the one entity the card is about is unavailable (or missing). A baked widget, which plays no
 * availability binding, then draws the theme's unavailable treatment because its template does.
 */
fun CardConverter.framedDesignProperties(
  card: CardConfig,
  snapshot: HaSnapshot,
): DesignProperties? {
  val base = designProperties(card.withoutLayoutKeys(), snapshot) ?: return null
  val entityId = unavailableFrameEntity(card) ?: return base
  val available = snapshot.states[entityId]?.let { HaLiveBindings.isAvailable(it.state) } ?: false
  if (available) return base
  return DesignProperties.build {
    base.values.forEach { (name, value) -> string(name, value) }
    string(UNAVAILABLE_DESIGN_PROPERTY, "1")
  }
}

/**
 * The keys of a card's config that place it in its view (a section's `grid_options`, the legacy
 * `layout_options`, a custom layout's `view_layout`): the host sizes the slot from them, no
 * converter draws them, so they never keep a card off its design.
 */
private val LAYOUT_KEYS = setOf("grid_options", "layout_options", "view_layout")

private fun CardConfig.withoutLayoutKeys(): CardConfig =
  if (raw.keys.none { it in LAYOUT_KEYS }) this
  else copy(raw = JsonObject(raw.filterKeys { it !in LAYOUT_KEYS }))

/**
 * The template input for [properties] from [framedDesignProperties]: the converter's own
 * [CardConverter.designTemplate], with the card's entity made unavailable when the properties say
 * so.
 */
fun CardConverter.framedDesignTemplate(properties: DesignProperties): DesignTemplateInput? {
  if (properties[UNAVAILABLE_DESIGN_PROPERTY] != "1") return designTemplate(properties)
  val own = DesignProperties.build {
    properties.values.forEach { (name, value) ->
      if (name != UNAVAILABLE_DESIGN_PROPERTY) string(name, value)
    }
  }
  val input = designTemplate(own) ?: return null
  val entityId = unavailableFrameEntity(input.card) ?: return input
  val unavailable =
    (input.snapshot.states[entityId] ?: EntityState(entityId, HaLiveBindings.UNAVAILABLE)).copy(
      state = HaLiveBindings.UNAVAILABLE
    )
  return input.copy(
    snapshot = input.snapshot.copy(states = input.snapshot.states + (entityId to unavailable))
  )
}
