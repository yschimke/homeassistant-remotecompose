package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a value that exists but can't be shown honestly reads as: a redacted (`21.xx`) or negative
 * duration. Never a number the entity didn't report (design principle 7, *real values only*).
 */
const val NO_VALUE: String = "—"

/**
 * The display string for [entity] under [snapshot], honouring the entity registry's display
 * precision ([ee.schimke.ha.design.DeviceInfo.displayPrecision]) when the snapshot carries it.
 *
 * This is the overload converters and hosts use: the document's initial bake and every host push of
 * `<id>.state` must agree, and both read the same snapshot.
 */
fun formatState(
  entity: EntityState?,
  snapshot: HaSnapshot,
  now: Instant = Clock.System.now(),
): String =
  formatState(
    entity,
    displayPrecision = entity?.let { snapshot.devices[it.entityId]?.displayPrecision },
    now = now,
  )

/**
 * The decimals [formatState] rounds [entity] to under [snapshot]: the entity registry's display
 * precision, else the entity's `display_precision` / `suggested_display_precision` attribute; null
 * for the device-class default. For hosts that hand the state on to be formatted elsewhere (Wear).
 */
fun displayPrecisionOf(entity: EntityState, snapshot: HaSnapshot): Int? =
  snapshot.devices[entity.entityId]?.displayPrecision
    ?: entity.attributes.int("display_precision")
    ?: entity.attributes.int("suggested_display_precision")

/**
 * Identical display strings for document authoring and live bindings on every platform, written to
 * read the way Home Assistant's own frontend shows a state:
 * - `unavailable` → `Unavailable`, `unknown` → `Unknown`, a missing entity → `Unavailable`.
 * - Timestamps (`device_class: timestamp`, and `event` / `button` / `input_button` / `scene`
 *   entities, whose state is when they last fired) → relative time (`3 h ago`, `in 5 min`), or a
 *   date once more than a week away. [now] is the reference instant.
 * - `binary_sensor` → the device class's words (`Open` / `Closed`, `Detected` / `Clear`, …).
 * - Numbers → rounded to [displayPrecision], else the entity's `display_precision` /
 *   `suggested_display_precision` attribute, else a default for the device class / unit; then
 *   currency (`£3.84`), durations (`1 h 05 min`), distances (`10 km`), compass bearings (`NE 50°`)
 *   and count units (`1 star`).
 * - Known enum / snake_case states (`heat_cool`, `not_home`, `on`) are humanised; free text
 *   (`V0.1.1`) is shown exactly as reported.
 */
fun formatState(
  entity: EntityState?,
  displayPrecision: Int? = null,
  now: Instant = Clock.System.now(),
): String = formatStateIn(entity, displayPrecision, now, TimeZone.currentSystemDefault())

/** [formatState] with an explicit [timeZone], for deterministic tests. */
internal fun formatStateIn(
  entity: EntityState?,
  displayPrecision: Int?,
  now: Instant,
  timeZone: TimeZone,
): String {
  val raw = entity?.state ?: return "Unavailable"
  if (raw == "unavailable") return "Unavailable"
  if (raw == "unknown") return "Unknown"
  val attributes = entity.attributes
  val domain = entity.entityId.substringBefore('.')
  val deviceClass = attributes.string("device_class")
  val unit = attributes.string("unit_of_measurement")?.trim().orEmpty()

  if (isTimestampEntity(domain, deviceClass)) {
    parseInstant(raw)?.let {
      return formatRelativeTime(it, now, timeZone)
    }
  }
  if (deviceClass == "date") {
    runCatching { LocalDate.parse(raw) }
      .getOrNull()
      ?.let {
        return formatDate(it)
      }
  }
  if (domain == "weather") {
    weatherConditionLabel(raw)?.let {
      return it
    }
  }
  if (domain == "binary_sensor") {
    binarySensorLabel(deviceClass, raw)?.let {
      return it
    }
  }
  if (isRedacted(raw)) return NO_VALUE
  if (isNumber(raw)) {
    val precision =
      displayPrecision
        ?: attributes.int("display_precision")
        ?: attributes.int("suggested_display_precision")
    return formatNumericState(
      raw,
      unit,
      deviceClass,
      precision,
      name = attributes.string("friendly_name"),
    )
  }
  return humanize(raw)
}

/**
 * [value] with its [unit], formatted the way [formatState] formats a numeric state: rounded to
 * [displayPrecision], else a default for [deviceClass] / [unit]. A redacted value (`21.xx`) is
 * [NO_VALUE]; a value that is not a number is returned as given (with its unit).
 */
fun formatValueWithUnit(
  value: String,
  unit: String?,
  deviceClass: String? = null,
  displayPrecision: Int? = null,
): String {
  val cleanUnit = unit?.trim().orEmpty()
  if (isRedacted(value)) return NO_VALUE
  if (!isNumber(value)) return joinUnit(value, cleanUnit)
  return formatNumericState(value, cleanUnit, deviceClass, displayPrecision, name = null)
}

/**
 * Whether [entity]'s display string is a time relative to now ([formatState] shows `3 h ago`), so a
 * host must refresh it as the clock moves even when the entity doesn't change.
 */
fun isRelativeTimeState(entity: EntityState?): Boolean {
  if (entity == null) return false
  val domain = entity.entityId.substringBefore('.')
  return isTimestampEntity(domain, entity.attributes.string("device_class")) &&
    parseInstant(entity.state) != null
}

// ---------------------------------------------------------------------------------------------
// Time

private val TIMESTAMP_DOMAINS = setOf("event", "button", "input_button", "scene")

private fun isTimestampEntity(domain: String, deviceClass: String?): Boolean =
  deviceClass == "timestamp" || domain in TIMESTAMP_DOMAINS

private fun parseInstant(raw: String): Instant? =
  if (raw.length < 10 || !raw[0].isDigit()) null else runCatching { Instant.parse(raw) }.getOrNull()

private const val MINUTE = 60L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * `just now`, `5 min ago`, `3 h ago`, `2 days ago` (and `in …` for the future), rounded to the
 * nearest unit with the thresholds of Home Assistant's `selectUnit` (45 s, 45 min, 22 h); a date
 * (`20 Apr 2026`) from a week either way, so a fixed past timestamp renders the same every day.
 */
internal fun formatRelativeTime(time: Instant, now: Instant, timeZone: TimeZone): String {
  val seconds = (now - time).inWholeSeconds
  val past = seconds >= 0
  val magnitude = abs(seconds).toDouble()
  fun relative(text: String) = if (past) "$text ago" else "in $text"
  val minutes = kotlin.math.round(magnitude / MINUTE).toLong()
  val hours = kotlin.math.round(magnitude / HOUR).toLong()
  val days = kotlin.math.round(magnitude / DAY).toLong()
  return when {
    magnitude < 45 -> if (past) "just now" else "now"
    minutes < 45 -> relative("$minutes min")
    hours < 22 -> relative("$hours h")
    days < 7 -> relative(if (days == 1L) "1 day" else "$days days")
    else -> formatDate(time.toLocalDateTime(timeZone).date)
  }
}

private val MONTHS =
  listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDate(date: LocalDate): String =
  "${date.day} ${MONTHS[date.month.ordinal]} ${date.year}"

// ---------------------------------------------------------------------------------------------
// Binary sensors: Home Assistant's English state translations by device class (off, on).

private val BINARY_SENSOR_LABELS: Map<String, Pair<String, String>> =
  mapOf(
    "battery" to ("Normal" to "Low"),
    "battery_charging" to ("Not charging" to "Charging"),
    "carbon_monoxide" to ("Clear" to "Detected"),
    "co" to ("Clear" to "Detected"),
    "cold" to ("Normal" to "Cold"),
    "connectivity" to ("Disconnected" to "Connected"),
    "door" to ("Closed" to "Open"),
    "garage_door" to ("Closed" to "Open"),
    "gas" to ("Clear" to "Detected"),
    "heat" to ("Normal" to "Hot"),
    "light" to ("No light" to "Light detected"),
    "lock" to ("Locked" to "Unlocked"),
    "moisture" to ("Dry" to "Wet"),
    "motion" to ("Clear" to "Detected"),
    "moving" to ("Not moving" to "Moving"),
    "occupancy" to ("Clear" to "Detected"),
    "opening" to ("Closed" to "Open"),
    "plug" to ("Unplugged" to "Plugged in"),
    "presence" to ("Away" to "Home"),
    "problem" to ("OK" to "Problem"),
    "running" to ("Not running" to "Running"),
    "safety" to ("Safe" to "Unsafe"),
    "smoke" to ("Clear" to "Detected"),
    "sound" to ("Clear" to "Detected"),
    "tamper" to ("Clear" to "Tampering detected"),
    "update" to ("Up-to-date" to "Update available"),
    "vibration" to ("Clear" to "Detected"),
    "window" to ("Closed" to "Open"),
  )

private fun binarySensorLabel(deviceClass: String?, raw: String): String? {
  val labels = BINARY_SENSOR_LABELS[deviceClass ?: return null] ?: return null
  return when (raw) {
    "off" -> labels.first
    "on" -> labels.second
    else -> null
  }
}

// ---------------------------------------------------------------------------------------------
// Enum / free text

private val ENUM_STATE = Regex("^[a-z0-9_]*[a-z][a-z0-9_]*$")

/** Home Assistant's wording where plain humanising reads wrong. */
private val ENUM_LABELS = mapOf("heat_cool" to "Heat/Cool", "not_home" to "Away")

/**
 * A known enum / snake_case state (`heat_cool`, `armed_away`) humanised; anything else — a version,
 * a commit title, a name — is the entity's own text and stays exactly as reported.
 */
private fun humanize(raw: String): String {
  if (!ENUM_STATE.matches(raw)) return raw
  ENUM_LABELS[raw]?.let {
    return it
  }
  return raw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}

// ---------------------------------------------------------------------------------------------
// Numbers

private val REDACTED = Regex("^-?\\d+\\.[xX]+$")
private val NUMBER = Regex("^([+-]?)(\\d*)(?:\\.(\\d*))?(?:[eE]([+-]?\\d+))?$")

private fun isRedacted(raw: String): Boolean = REDACTED.matches(raw)

/**
 * A plain decimal (`21.5`, `-3`, `1e3`) that is finite. Stricter than `toDoubleOrNull`, which on
 * the JVM also takes `5f` or ` 7 `: the embed's JavaScript port (`embed/ha-card.js`) must agree.
 */
private fun isNumber(raw: String): Boolean =
  NUMBER.matches(raw) && raw.toDoubleOrNull()?.isFinite() == true

private fun formatNumericState(
  raw: String,
  unit: String,
  deviceClass: String?,
  precision: Int?,
  name: String?,
): String {
  val value = raw
  val number = value.toDouble()
  if (deviceClass == "duration")
    formatDuration(number, unit)?.let {
      return it
    }
  currencyOf(unit, deviceClass)?.let { currency ->
    return formatMoney(value, currency, precision)
  }
  if (deviceClass == "wind_direction" || (unit == "°" && name.mentionsBearing())) {
    val degrees = formatDecimal(value, precision ?: 0, precision ?: 0) ?: value
    return "${compassPoint(number)} $degrees°"
  }
  if (unit == "m" && (deviceClass == null || deviceClass == "distance") && abs(number) >= 1000) {
    val km = formatDecimal(scaleDown(value, 3), 0, precision ?: 1) ?: return joinUnit(value, unit)
    return "$km km"
  }
  val (min, max) =
    if (precision != null) precision to precision else 0 to defaultDecimals(deviceClass, unit)
  val formatted = formatDecimal(value, min, max) ?: value
  val shownUnit =
    when {
      unit.repeatsName(name) -> ""
      else -> countUnit(unit, formatted)
    }
  return joinUnit(formatted, shownUnit)
}

private fun joinUnit(value: String, unit: String): String =
  when {
    unit.isEmpty() -> value
    unit == "°" -> "$value°"
    else -> "$value $unit"
  }

/**
 * The most decimals worth showing for a reading with no precision of its own: what Home Assistant
 * integrations commonly suggest for the device class / unit. Trailing zeros are still trimmed, so a
 * default never adds digits the entity didn't report.
 */
private fun defaultDecimals(deviceClass: String?, unit: String): Int =
  when (unit) {
    "°C",
    "°F",
    "℃",
    "℉",
    "K" -> 1
    "%" -> 0
    "W",
    "Wh",
    "lx",
    "ppm",
    "ppb",
    "µg/m³",
    "μg/m³",
    "mg/m³",
    "dB",
    "dBm",
    "dBA",
    "hPa",
    "mbar",
    "Pa",
    "W/m²",
    "mA",
    "g",
    "cm",
    "mL" -> 0
    "kW",
    "MW",
    "kWh",
    "MWh",
    "A",
    "inHg",
    "bar",
    "m³" -> 2
    "V",
    "kPa",
    "psi",
    "km/h",
    "mph",
    "m/s",
    "kn",
    "ft/s",
    "km",
    "mi",
    "mm",
    "in",
    "mm/h",
    "in/h",
    "L",
    "gal",
    "kg",
    "lb" -> 1
    else ->
      when (deviceClass) {
        "temperature" -> 1
        "humidity",
        "battery",
        "moisture",
        "illuminance",
        "aqi" -> 0
        else -> 2
      }
  }

/**
 * [raw] HALF_UP-rounded to [maxDecimals], trailing zeros trimmed down to [minDecimals]; null when
 * [raw] isn't a decimal this can represent (the caller shows it as given). Decimal-string rounding
 * avoids JVM BigDecimal and binary floating-point skew, so every platform agrees.
 */
internal fun formatDecimal(raw: String, minDecimals: Int, maxDecimals: Int): String? {
  val rounded = roundDecimal(raw, maxDecimals) ?: return null
  if (maxDecimals <= minDecimals || '.' !in rounded) return rounded
  val (whole, fraction) = rounded.split('.')
  val kept = fraction.trimEnd('0').padEnd(minDecimals, '0')
  return if (kept.isEmpty()) whole else "$whole.$kept"
}

private fun roundDecimal(raw: String, decimals: Int): String? {
  val text = raw
  if (text.toDoubleOrNull()?.isFinite() != true) return null
  val match = NUMBER.matchEntire(text) ?: return null
  val integer = match.groupValues[2]
  val fraction = match.groupValues[3]
  val exponentText = match.groupValues[4]
  val exponent = if (exponentText.isEmpty()) 0 else exponentText.toIntOrNull() ?: return null
  val zero = if (decimals == 0) "0" else "0." + "0".repeat(decimals)
  // Finite Double underflow may still contain an arbitrarily large negative exponent.
  if (exponent < -400) return zero
  if (exponent > 400) return null
  val digits = integer + fraction
  if (digits.isEmpty()) return null
  val decimalAt = integer.length + exponent
  val whole =
    when {
        decimalAt <= 0 -> "0"
        decimalAt >= digits.length ->
          digits + "0".repeat((decimalAt - digits.length).coerceAtMost(400))
        else -> digits.take(decimalAt)
      }
      .trimStart('0')
      .ifEmpty { "0" }
  val fractional =
    when {
      decimalAt <= 0 -> "0".repeat((-decimalAt).coerceAtMost(400)) + digits
      decimalAt >= digits.length -> ""
      else -> digits.drop(decimalAt)
    }.padEnd(decimals + 1, '0')
  var scaled = (whole + fractional.take(decimals)).trimStart('0').ifEmpty { "0" }
  if (fractional[decimals] >= '5') {
    val chars = scaled.toCharArray()
    var carry = true
    for (i in chars.lastIndex downTo 0) {
      if (!carry) break
      if (chars[i] == '9') chars[i] = '0'
      else {
        chars[i] = chars[i] + 1
        carry = false
      }
    }
    scaled = (if (carry) "1" else "") + chars.concatToString()
  }
  val padded = scaled.padStart(decimals + 1, '0')
  val result =
    if (decimals == 0) padded else padded.dropLast(decimals) + "." + padded.takeLast(decimals)
  val isZero = result.all { it == '0' || it == '.' }
  return if (match.groupValues[1] == "-" && !isZero) "-$result" else result
}

/** [raw] divided by 10^[places], as a decimal string (exact: it only moves the decimal point). */
private fun scaleDown(raw: String, places: Int): String {
  val match = NUMBER.matchEntire(raw) ?: return raw
  val exponent = match.groupValues[4].toIntOrNull() ?: 0
  val mantissa = raw.substringBefore('e').substringBefore('E')
  return "${mantissa}e${exponent - places}"
}

// ---------------------------------------------------------------------------------------------
// Currency

private data class Currency(val code: String, val per: String?)

/**
 * ISO 4217 codes recognised from a bare unit; any code is accepted with `device_class: monetary`.
 */
private val CURRENCY_SYMBOLS =
  mapOf(
    "GBP" to "£",
    "EUR" to "€",
    "USD" to "$",
    "JPY" to "¥",
    "CNY" to "CN¥",
    "INR" to "₹",
    "KRW" to "₩",
    "AUD" to "A$",
    "CAD" to "CA$",
    "NZD" to "NZ$",
    "HKD" to "HK$",
    "MXN" to "MX$",
    "BRL" to "R$",
    "ILS" to "₪",
    "VND" to "₫",
    "TWD" to "NT$",
    "PHP" to "₱",
  )
private val CURRENCY_CODES =
  CURRENCY_SYMBOLS.keys +
    setOf(
      "CHF",
      "SEK",
      "NOK",
      "DKK",
      "PLN",
      "CZK",
      "HUF",
      "RON",
      "BGN",
      "ZAR",
      "SGD",
      "THB",
      "TRY",
      "IDR",
      "MYR",
      "AED",
      "SAR",
      "ISK",
    )
private val ZERO_DECIMAL_CURRENCIES = setOf("JPY", "KRW", "VND", "ISK", "HUF")
private val CURRENCY_UNIT = Regex("^([A-Z]{3})(?:\\s*/\\s*(.+))?$")

private fun currencyOf(unit: String, deviceClass: String?): Currency? {
  val match = CURRENCY_UNIT.matchEntire(unit) ?: return null
  val code = match.groupValues[1]
  if (code !in CURRENCY_CODES && deviceClass != "monetary") return null
  return Currency(code, match.groupValues[2].ifEmpty { null })
}

/** `£3.84`, `£0.25/kWh`, `CHF 3.84`: the English-locale currency display Home Assistant uses. */
private fun formatMoney(value: String, currency: Currency, precision: Int?): String {
  val fixed = if (currency.code in ZERO_DECIMAL_CURRENCIES) 0 else 2
  // A rate (per kWh, per m³) keeps the sub-unit digits tariffs are quoted in.
  val (min, max) =
    when {
      precision != null -> precision to precision
      currency.per != null -> fixed to maxOf(fixed, 4)
      else -> fixed to fixed
    }
  val amount = formatDecimal(value, min, max) ?: value
  val negative = amount.startsWith("-")
  val magnitude = amount.removePrefix("-")
  val symbol = CURRENCY_SYMBOLS[currency.code]
  val money = if (symbol != null) "$symbol$magnitude" else "${currency.code} $magnitude"
  val per = currency.per?.let { "/$it" }.orEmpty()
  return (if (negative) "-" else "") + money + per
}

// ---------------------------------------------------------------------------------------------
// Durations, bearings, names, counts

/**
 * `1 h 05 min`, `5 min`, `45 s`, `2 d 3 h` for a `device_class: duration` reading in [unit] (`d`,
 * `h`, `min`, `s`, `ms`; seconds when absent). A negative duration is [NO_VALUE]; null for a unit
 * this doesn't know, which then formats as a plain number.
 */
private fun formatDuration(value: Double, unit: String): String? {
  val seconds =
    when (unit) {
      "d" -> value * DAY
      "h" -> value * HOUR
      "min" -> value * MINUTE
      "s",
      "" -> value
      "ms" -> value / 1000
      "µs",
      "μs" -> value / 1_000_000
      else -> return null
    }
  if (seconds < 0) return NO_VALUE
  val total = kotlin.math.round(seconds).toLong()
  return when {
    total < MINUTE -> "$total s"
    total < HOUR -> {
      val rest = total % MINUTE
      if (rest == 0L || unit !in setOf("s", "", "ms", "µs", "μs")) "${total / MINUTE} min"
      else "${total / MINUTE} min ${rest.toString().padStart(2, '0')} s"
    }
    total < DAY -> {
      val minutes = (total % HOUR) / MINUTE
      if (minutes == 0L) "${total / HOUR} h"
      else "${total / HOUR} h ${minutes.toString().padStart(2, '0')} min"
    }
    else -> {
      val hours = (total % DAY) / HOUR
      if (hours == 0L) "${total / DAY} d" else "${total / DAY} d $hours h"
    }
  }
}

private val COMPASS =
  listOf(
    "N",
    "NNE",
    "NE",
    "ENE",
    "E",
    "ESE",
    "SE",
    "SSE",
    "S",
    "SSW",
    "SW",
    "WSW",
    "W",
    "WNW",
    "NW",
    "NNW",
  )

/** The 16-point compass point for a bearing in degrees. */
internal fun compassPoint(degrees: Double): String {
  val normalized = ((degrees % 360) + 360) % 360
  return COMPASS[((normalized + 11.25) / 22.5).toInt() % 16]
}

private fun String?.mentionsBearing(): Boolean {
  val name = this?.lowercase() ?: return false
  return "bearing" in name || "direction" in name
}

/** A unit the entity's name already ends with (`UV index` on "Garden UV index"): shown once. */
private fun String.repeatsName(name: String?): Boolean {
  if (name == null || length < 3 || none { it.isLetter() }) return false
  val lowerName = name.trim().lowercase()
  val lowerUnit = lowercase()
  if (!lowerName.endsWith(lowerUnit)) return false
  val before = lowerName.length - lowerUnit.length - 1
  return before < 0 || !lowerName[before].isLetterOrDigit()
}

/** Count units Home Assistant integrations report in the plural: singular ↔ plural. */
private val COUNT_UNITS: Map<String, String> =
  listOf(
      "alert",
      "commit",
      "cycle",
      "device",
      "download",
      "email",
      "error",
      "event",
      "file",
      "follower",
      "fork",
      "issue",
      "item",
      "message",
      "notification",
      "package",
      "pull request",
      "request",
      "star",
      "step",
      "task",
      "time",
      "update",
      "visitor",
      "warning",
      "watcher",
    )
    .associateWith { "${it}s" } + mapOf("entity" to "entities", "person" to "people")
private val COUNT_SINGULAR = COUNT_UNITS.entries.associate { (one, many) -> many to one }

/** `1 star`, `2 stars`: a known count unit agreeing with [formatted]; any other unit unchanged. */
private fun countUnit(unit: String, formatted: String): String {
  val singular = COUNT_SINGULAR[unit] ?: unit.takeIf { it in COUNT_UNITS } ?: return unit
  return if (formatted == "1" || formatted == "-1") singular else COUNT_UNITS.getValue(singular)
}

// ---------------------------------------------------------------------------------------------

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
  (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..10 }

/**
 * A weather entity's condition as Home Assistant's frontend names it in English
 * (`component.weather.entity_component._.state`): `partlycloudy` reads "Partly cloudy", not
 * "Partlycloudy". Null for a condition HA doesn't list.
 */
fun weatherConditionLabel(condition: String): String? = WEATHER_CONDITION_LABELS[condition]

private val WEATHER_CONDITION_LABELS =
  mapOf(
    "clear-night" to "Clear, night",
    "cloudy" to "Cloudy",
    "exceptional" to "Exceptional",
    "fog" to "Fog",
    "hail" to "Hail",
    "lightning" to "Lightning",
    "lightning-rainy" to "Lightning, rainy",
    "partlycloudy" to "Partly cloudy",
    "pouring" to "Pouring",
    "rainy" to "Rainy",
    "snowy" to "Snowy",
    "snowy-rainy" to "Snowy, rainy",
    "sunny" to "Sunny",
    "windy" to "Windy",
    "windy-variant" to "Windy",
  )
