package ee.schimke.ha.design

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.temperatureUnitFor
import kotlin.math.round
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * **Entity actions**: the named actions a card design's control may send on the card's own entity
 * (its slot), such as a garage door's Open, Stop and Close. A design names one (`close_cover`) and
 * its slot (`cover.s0`); the host resolves it here against the slot's real entity and its current
 * state, and runs the service call it gives. Every host follows [resolve]: the app
 * (`CardTemplatePlayback.resolveEntityAction`) and the browser card (`resolveEntityAction` in
 * `embed/ha-card.js`, a port held to the same cases by `embed/test/entity-actions.test.mjs`).
 *
 * A template is remote content, so it never names a service: only an action this table lists for
 * the slot entity's own domain, which the host runs on that entity alone, with service data it
 * computes itself (a thermostat's next set point). An action the table doesn't list, or the entity
 * doesn't support (`supported_features`), resolves to nothing.
 *
 * A design sends one as its own host action, [NAME], not as the `ha` tap: a host that predates
 * entity actions ignores it, so the control does nothing there rather than run the card's tap (a
 * cover's toggle, which would open a closed door on Close).
 */
object CardEntityActions {
  /** The host action name a design sends an entity action under. */
  const val NAME: String = "ha.entity_action"

  /** One Home Assistant service call: `<domain>.<service>` on [entityId] with [data]. */
  data class ServiceCall(
    val domain: String,
    val service: String,
    val entityId: String,
    val data: JsonObject = JsonObject(emptyMap()),
  )

  /** How one named action resolves on [entity] (the real one) in [snapshot]. */
  private fun interface Rule {
    fun call(entityId: String, entity: EntityState?, snapshot: HaSnapshot?): ServiceCall?
  }

  /** `<domain>.<service>` on the entity, when it reports [feature] (or reports no features). */
  private fun service(service: String, feature: Int = 0) = Rule { entityId, entity, _ ->
    if (feature != 0 && !entity.supports(feature)) null
    else ServiceCall(entityId.substringBefore('.'), service, entityId)
  }

  /** A thermostat's set point one step ([direction] −1 or +1) on, kept in its range. */
  private fun stepTemperature(direction: Int) = Rule { entityId, entity, snapshot ->
    val target = entity.number("temperature") ?: return@Rule null
    val unit = (snapshot ?: HaSnapshot()).temperatureUnitFor(entity)
    val step = entity.number("target_temp_step") ?: if (unit == FAHRENHEIT) 1.0 else 0.5
    val min = entity.number("min_temp") ?: CardBindingRecipes.CLIMATE_MIN_TEMP
    val max = entity.number("max_temp") ?: CardBindingRecipes.CLIMATE_MAX_TEMP
    val next = setPoint(target + direction * step, min, max)
    ServiceCall("climate", "set_temperature", entityId, JsonObject(mapOf("temperature" to next)))
  }

  private val ON_OFF =
    mapOf(
      "turn_on" to service("turn_on"),
      "turn_off" to service("turn_off"),
      "toggle" to service("toggle"),
    )

  /**
   * The actions a design may name, by the slot entity's domain. A cover's follow Home Assistant's
   * `CoverEntityFeature` bits (1 open, 2 close, 8 stop).
   */
  private val RULES: Map<String, Map<String, Rule>> =
    mapOf(
      "cover" to
        mapOf(
          "open_cover" to service("open_cover", feature = 1),
          "close_cover" to service("close_cover", feature = 2),
          "stop_cover" to service("stop_cover", feature = 8),
          "toggle" to service("toggle"),
        ),
      "climate" to
        mapOf(
          "turn_on" to service("turn_on"),
          "turn_off" to service("turn_off"),
          "temperature_up" to stepTemperature(+1),
          "temperature_down" to stepTemperature(-1),
        ),
      "light" to ON_OFF,
      "switch" to ON_OFF,
      "fan" to ON_OFF,
      "input_boolean" to ON_OFF,
    )

  /** The action names a design may send on an entity of [domain]. */
  fun actionsFor(domain: String): Set<String> = RULES[domain]?.keys.orEmpty()

  /** Every domain with entity actions, and its actions: what a design's `action` may name. */
  val ACTIONS: Map<String, Set<String>>
    get() = RULES.mapValues { it.value.keys }

  /**
   * The service call [action] makes on [entityId] (a real entity) in [snapshot], or null when the
   * action isn't one of its domain's or the entity doesn't support it.
   */
  fun resolve(entityId: String, action: String, snapshot: HaSnapshot?): ServiceCall? {
    val rule = RULES[entityId.substringBefore('.', "")]?.get(action) ?: return null
    return rule.call(entityId, snapshot?.states?.get(entityId), snapshot)
  }

  /**
   * A set point as Home Assistant takes it: within [min]..[max], rounded to hundredths so a step of
   * 0.1 doesn't send 21.200000000000003, a whole number as an integer.
   */
  private fun setPoint(value: Double, min: Double, max: Double): JsonPrimitive {
    val clamped = value.coerceIn(min, maxOf(min, max))
    val rounded = round(clamped * 100) / 100
    return if (rounded == round(rounded)) JsonPrimitive(rounded.toLong())
    else JsonPrimitive(rounded)
  }

  private fun EntityState?.number(name: String): Double? =
    (this?.attributes?.get(name) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf {
      it.isFinite()
    }

  /** Whether the entity reports [feature], or reports no `supported_features` at all. */
  private fun EntityState?.supports(feature: Int): Boolean {
    val features = number("supported_features") ?: return true
    return (features.toLong() and feature.toLong()) != 0L
  }

  private const val FAHRENHEIT = "°F"
}
