package ee.schimke.terrazzo.shared

import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.temperatureUnitFor
import ee.schimke.ha.rc.formatValueWithUnit
import kotlinx.serialization.json.*

/** Host derivations for GaugeCardConverter.designBindings and the thermostat's DialCard. */
internal fun instrumentTemplateBindings(
  card: CardConfig,
  snapshot: HaSnapshot,
): Map<String, RcNamedValue> {
  val id = (card.raw["entity"] as? JsonPrimitive)?.contentOrNull ?: return emptyMap()
  val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(id), 0)
  val entity = snapshot.states[id]
  val attributes = entity?.attributes ?: JsonObject(emptyMap())
  fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
  fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
  fun numberLabel(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else formatValueWithUnit(value.toString(), null, displayPrecision = 1)
  return buildMap {
    fun text(key: String, value: String) {
      put(key, RcNamedValue.Text(value))
    }
    fun number(key: String, value: Double) {
      put(key, RcNamedValue.FloatValue(value.toFloat()))
    }
    when (card.type) {
      "gauge" -> {
        val min = card.raw.number("min") ?: 0.0
        val max = card.raw.number("max") ?: 100.0
        number("card.min", min)
        number("card.max", max)
        val severity = card.raw["severity"] as? JsonObject
        for (band in listOf("green", "yellow", "red")) {
          // The converter uses an unreachable threshold for an unset severity band.
          number("card.severity_$band", severity?.number(band) ?: 1e30)
        }
        number(
          "$slot.numeric_state",
          entity?.state?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: min,
        )
        val unit = card.raw.text("unit") ?: attributes.text("unit_of_measurement")
        if (unit != null) text("card.range", "${numberLabel(min)} – ${numberLabel(max)} $unit")
      }
      "thermostat" -> {
        val current = attributes.number("current_temperature")
        val target = attributes.number("temperature")
        val min = attributes.number("min_temp") ?: 7.0
        val max = attributes.number("max_temp") ?: 35.0
        val span = (max - min).coerceAtLeast(0.0001)
        val unit = snapshot.temperatureUnitFor(entity)
        number(
          "$slot.value_fraction",
          (((current ?: target ?: min) - min) / span).coerceIn(0.0, 1.0),
        )
        // Clear removed targets and labels instead of leaving the previous HA value in the player.
        number(
          "$slot.target_fraction",
          target?.let { ((it - min) / span).coerceIn(0.0, 1.0) } ?: 0.0,
        )
        text(
          "$slot.attributes.current_temperature_label",
          (current ?: target)?.let { numberLabel(it) + unit } ?: "—",
        )
        text(
          "$slot.attributes.temperature_label",
          target?.let { "↑ ${numberLabel(it)}$unit" } ?: "—",
        )
        val mode = entity?.state ?: "off"
        val action = attributes.text("hvac_action")
        text(
          "$slot.attributes.hvac_action_label",
          when (action) {
            "heating" -> "Heating"
            "cooling" -> "Cooling"
            "drying" -> "Drying"
            "fan" -> "Fan"
            "idle" -> "Idle"
            else -> mode.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
          },
        )
        val accent =
          when {
            action == "heating" || mode == "heat" -> 0
            action == "cooling" || mode == "cool" -> 1
            action == "drying" || mode == "dry" -> 2
            action == "fan" || mode == "fan_only" -> 3
            mode in setOf("auto", "heat_cool") -> 4
            else -> 5
          }
        put("$slot.accent_index", RcNamedValue.Integer(accent))
      }
    }
  }
}
