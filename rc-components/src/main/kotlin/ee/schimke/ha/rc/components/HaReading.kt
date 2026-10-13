@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.selectIfLt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------------------------
// Layer 1 of the card data hierarchy (docs/architecture/card-component-system.md):
//
//   raw reading  ──►  visual meter  ──►  typed measurement
//   HaReading         RemoteHaArcMeter    RemoteHaThermometer
//                     RemoteHaBarMeter    RemoteHaBattery
//                     RemoteHaReadingText RemoteHaMeasurement (picks one by kind)
//
// A reading is what Home Assistant says about one numeric entity, independent of how it is drawn.
// It owns the live-update contract: its text is the `<id>.state` binding (the host's formatState)
// and its value the `<id>.numeric_state` binding, so every visual built on it keeps up with Home
// Assistant through named bindings (path 1 of live-card-updates.md) with no re-encode. Everything a
// visual derives — the fill fraction, the band colour — is computed in-document from those two.
// ---------------------------------------------------------------------------------------------

/**
 * One numeric entity's reading.
 *
 * @property text the formatted state, unit included (`21.5 °C`) — the `<id>.state` binding.
 * @property value the parsed number — the `<id>.numeric_state` binding.
 * @property unit the unit alone (`°C`), for presentations that set it apart from the number.
 * @property min lower end of the scale a visual maps [value] onto.
 * @property max upper end of the scale.
 * @property kind what is being measured; decides the typed visual, colour rule and default range.
 */
data class HaReading(
  val entityId: String?,
  val name: String,
  val text: RemoteString,
  val value: RemoteFloat,
  val unit: String?,
  val min: Double,
  val max: Double,
  val kind: HaMeasureKind = HaMeasureKind.Generic,
) {
  companion object {
    /**
     * A reading bound to [entityId]'s live state. [formatted] must be what the host pushes to
     * `<id>.state` (`formatState(entity)`), [numeric] the parsed state. A null [entityId] gives a
     * static reading (previews, a mock in the UI builder).
     *
     * The range defaults to [kind]'s typical scale for [unit] when [min] / [max] are null.
     */
    fun live(
      entityId: String?,
      name: String,
      formatted: String,
      numeric: Float,
      unit: String?,
      kind: HaMeasureKind = HaMeasureKind.Generic,
      min: Double? = null,
      max: Double? = null,
    ): HaReading {
      val range = kind.defaultRange(unit)
      return HaReading(
        entityId = entityId,
        name = name,
        text = LiveValues.state(entityId, formatted),
        value = LiveValues.numericState(entityId, numeric),
        unit = unit,
        min = min ?: range.start,
        max = max ?: range.endInclusive,
        kind = kind,
      )
    }

    /** A constant reading, for stickers and mocks. */
    fun static(
      name: String,
      value: Float,
      unit: String?,
      kind: HaMeasureKind = HaMeasureKind.Generic,
      min: Double? = null,
      max: Double? = null,
    ): HaReading =
      live(
        entityId = null,
        name = name,
        formatted = formatReading(value, unit),
        numeric = value,
        unit = unit,
        kind = kind,
        min = min,
        max = max,
      )

    /** `21.5 °C`, `46 %`, `12`: HA's spelling of a number and its unit. */
    fun formatReading(value: Float, unit: String?): String {
      val number = if (value == value.toInt().toFloat()) value.toInt().toString() else "$value"
      return when {
        unit.isNullOrBlank() -> number
        unit == "%" -> "$number %"
        else -> "$number $unit"
      }
    }
  }
}

/**
 * What a reading measures. Classified from HA's `device_class` (and the unit, for sensors without
 * one), it picks the typed visual ([visual]), the colour rule ([colorRule]) and a default scale
 * ([defaultRange]) — the type-specific layer above the generic meters.
 */
enum class HaMeasureKind(val visual: HaMeasureVisual) {
  Temperature(HaMeasureVisual.Thermometer),
  Humidity(HaMeasureVisual.Arc),
  Battery(HaMeasureVisual.Battery),
  Power(HaMeasureVisual.Arc),
  Illuminance(HaMeasureVisual.Bar),
  Percentage(HaMeasureVisual.Bar),
  Generic(HaMeasureVisual.Arc);

  /** The scale a reading of this kind maps onto when the card doesn't give `min` / `max`. */
  fun defaultRange(unit: String?): ClosedFloatingPointRange<Double> =
    when (this) {
      Temperature -> if (unit.isFahrenheit()) 14.0..104.0 else -10.0..40.0
      Humidity,
      Battery,
      Percentage,
      Generic -> 0.0..100.0
      Power -> if (unit?.trim() == "kW") 0.0..5.0 else 0.0..3000.0
      Illuminance -> 0.0..1000.0
    }

  /** How a visual of this kind colours its value. */
  fun colorRule(unit: String?): HaMeterColor =
    when (this) {
      Temperature ->
        HaMeterColor.Ramp(
          base = HaTemperatureColors.Freezing,
          stops =
            HaTemperatureColors.stopsCelsius.map { (celsius, color) ->
              (if (unit.isFahrenheit()) celsius * 9f / 5f + 32f else celsius) to color
            },
        )
      Battery -> HaMeterColor.LowIsBad(warningBelow = 30f, criticalBelow = 15f)
      Humidity -> HaMeterColor.Fixed(Color(0xFF0288D1))
      Power -> HaMeterColor.Fixed(Color(0xFFFFA000))
      Illuminance -> HaMeterColor.Fixed(Color(0xFFFBC02D))
      Percentage,
      Generic -> HaMeterColor.Fixed(HaStatusColors.None)
    }

  companion object {
    /** Classify by HA `device_class`, falling back to the unit. */
    fun of(deviceClass: String?, unit: String?): HaMeasureKind =
      when (deviceClass) {
        "temperature" -> Temperature
        "humidity",
        "moisture" -> Humidity
        "battery" -> Battery
        "power",
        "energy" -> Power
        "illuminance" -> Illuminance
        else ->
          when (unit?.trim()) {
            "°C",
            "°F" -> Temperature
            "W",
            "kW" -> Power
            "lx" -> Illuminance
            "%" -> Percentage
            else -> Generic
          }
      }

    /** Parse a builder / config spelling (`temperature`, `battery`, …); unknown is [Generic]. */
    fun parse(name: String): HaMeasureKind =
      entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: Generic
  }
}

/** The visual family a [HaMeasureKind] draws with. */
enum class HaMeasureVisual {
  Thermometer,
  Battery,
  Arc,
  Bar,
}

private fun String?.isFahrenheit() = this?.trim() == "°F"

/**
 * How a meter colours its value. Every rule is evaluated **in-document** from the live value, so a
 * reading crossing a band recolours with no re-encode.
 */
sealed interface HaMeterColor {
  /** One colour whatever the value. */
  data class Fixed(val color: Color) : HaMeterColor

  /** [base] below the first stop; otherwise the colour of the highest stop the value reaches. */
  data class Ramp(val base: Color, val stops: List<Pair<Float, Color>>) : HaMeterColor

  /** Normal at or above [warningBelow], warning below it, critical below [criticalBelow]. */
  data class LowIsBad(val warningBelow: Float, val criticalBelow: Float) : HaMeterColor
}

/** The colour [rule] gives [value], as a document expression. */
fun meterColor(value: RemoteFloat, rule: HaMeterColor): RemoteColor =
  when (rule) {
    is HaMeterColor.Fixed -> rule.color.rc
    is HaMeterColor.Ramp ->
      rule.stops
        .sortedBy { it.first }
        .fold(rule.base.rc) { below, (threshold, color) ->
          value.isGreaterThanOrEqualTo(threshold.rf).select(color.rc, below)
        }
    is HaMeterColor.LowIsBad ->
      value
        .isGreaterThanOrEqualTo(rule.warningBelow.rf)
        .select(
          HaStatusColors.Normal.rc,
          value
            .isGreaterThanOrEqualTo(rule.criticalBelow.rf)
            .select(HaStatusColors.Warning.rc, HaStatusColors.Critical.rc),
        )
  }

/** This reading's colour under its kind's rule. */
fun HaReading.color(): RemoteColor = meterColor(value, kind.colorRule(unit))

/**
 * Where the value sits on the reading's scale, `0..1`, clamped — computed in-document from the live
 * value and tweened so each host update animates rather than snaps.
 */
@Composable
fun HaReading.fraction(durationSeconds: Float = 0.45f): RemoteFloat =
  normalizedFraction(
    animateRemoteFloat(value, durationSeconds = durationSeconds, easing = RcEasing.Standard),
    min.toFloat().rf,
    max.toFloat().rf,
  )

/** `(value - min) / (max - min)` clamped to `0..1`; a degenerate span reads as empty. */
fun normalizedFraction(value: RemoteFloat, min: RemoteFloat, max: RemoteFloat): RemoteFloat {
  val span = (max - min).let { selectIfLt(it, 0.0001f.rf, 0.0001f.rf, it) }
  val raw = (value - min) / span
  // clamp: min(raw, 1) then max(…, 0) spelled as -min(-x, 0).
  return -((-(raw.min(1f.rf))).min(0f.rf))
}

/** The reading's name: the template `<id>.name` binding, else the literal. */
@Composable fun HaReading.nameText(): RemoteString = entityName(entityId, name)

/** `min – max unit`: a reading's scale as one caption. */
fun HaReading.rangeText(): RemoteString = gaugeRangeText(min, max, unit).trim().rs
