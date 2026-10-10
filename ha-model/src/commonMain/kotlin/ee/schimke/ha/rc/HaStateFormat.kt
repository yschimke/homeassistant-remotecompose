package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.jsonPrimitive

/** Identical display strings for document authoring and live bindings on every platform. */
fun formatState(entity: EntityState?): String {
  val raw = entity?.state ?: return "Unavailable"
  if (raw == "unavailable" || raw == "unknown") return "Unavailable"
  val unit = entity.attributes["unit_of_measurement"]?.jsonPrimitive?.content
  val normalized = normalizeNumericValue(raw)
  if (normalized != null) return formatValueWithUnit(normalized, unit)
  return raw.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}

fun formatValueWithUnit(value: String, unit: String?): String {
  val normalized = normalizeNumericValue(value) ?: value
  val cleanUnit = unit?.trim().orEmpty()
  return if (cleanUnit.isEmpty()) normalized else "$normalized $cleanUnit"
}

/** Decimal-string HALF_UP rounding avoids JVM BigDecimal and binary floating-point skew. */
private fun normalizeNumericValue(raw: String): String? {
  Regex("^(-?\\d+)\\.[xX]+$").matchEntire(raw)?.let {
    return it.groupValues[1]
  }
  if (raw.toDoubleOrNull()?.isFinite() != true) return null
  val match =
    Regex("^([+-]?)(\\d*)(?:\\.(\\d*))?(?:[eE]([+-]?\\d+))?$").matchEntire(raw) ?: return null
  val integer = match.groupValues[2]
  val fraction = match.groupValues[3]
  val exponent = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
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
    }.padEnd(3, '0')
  var scaled = (whole + fractional.take(2)).trimStart('0').ifEmpty { "0" }
  if (fractional[2] >= '5') {
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
  val padded = scaled.padStart(3, '0')
  val decimals = padded.takeLast(2).trimEnd('0')
  val result = padded.dropLast(2) + if (decimals.isEmpty()) "" else ".$decimals"
  return if (match.groupValues[1] == "-" && result != "0") "-$result" else result
}
