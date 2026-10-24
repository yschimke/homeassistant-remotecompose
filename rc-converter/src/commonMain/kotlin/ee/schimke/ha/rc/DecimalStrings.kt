package ee.schimke.ha.rc

/**
 * The JDK's decimal formatting of a `Double`, written out for the targets without one: a value is
 * its shortest decimal representation (`BigDecimal.valueOf`), rounded half up in decimal, as
 * `java.util.Formatter` and `BigDecimal` do. The host tests check each against the JDK.
 */
internal object DecimalStrings {
  /** `"%.{decimals}f".format(Locale.ROOT, value)`. */
  fun formatFixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val d = parse(value)
    // The formatter keeps the sign of a value that rounds to zero ("-0.0").
    return (if (d.negative) "-" else "") + plain(rounded(d, decimals), decimals)
  }

  /** `BigDecimal.valueOf(value).stripTrailingZeros().scale()`, at least 0. */
  fun decimalPlaces(value: Double): Int = parse(value).scale.coerceAtLeast(0)

  /** `BigDecimal.valueOf(value).setScale(digits, HALF_UP).toPlainString()`. */
  fun plainHalfUp(value: Double, digits: Int): String {
    val d = parse(value)
    val unscaled = rounded(d, digits)
    // BigDecimal has no negative zero.
    val sign = if (d.negative && unscaled.any { it != '0' }) "-" else ""
    return sign + plain(unscaled, digits)
  }

  /** `BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)).toDouble()`. */
  fun sum(a: Double, b: Double): Double {
    val x = parse(a)
    val y = parse(b)
    val scale = maxOf(x.scale, y.scale, 0)
    val xs = x.digits + "0".repeat(scale - x.scale)
    val ys = y.digits + "0".repeat(scale - y.scale)
    val (negative, magnitude) =
      when {
        x.negative == y.negative -> x.negative to add(xs, ys)
        compare(xs, ys) >= 0 -> x.negative to subtract(xs, ys)
        else -> y.negative to subtract(ys, xs)
      }
    if (magnitude == "0") return 0.0
    return ((if (negative) "-" else "") + magnitude + "E" + (-scale)).toDouble()
  }

  /** A finite value as `digits × 10^-scale`, trailing zeros removed; zero is `"0"`, scale 0. */
  private class Decimal(val negative: Boolean, val digits: String, val scale: Int)

  private fun parse(value: Double): Decimal {
    require(value.isFinite()) { "not finite: $value" }
    var text = value.toString()
    val negative = text.startsWith('-')
    if (negative) text = text.substring(1)
    val e = text.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (e < 0) text else text.substring(0, e)
    val exponent = if (e < 0) 0 else text.substring(e + 1).removePrefix("+").toInt()
    val dot = mantissa.indexOf('.')
    val fraction = if (dot < 0) "" else mantissa.substring(dot + 1)
    val all = (if (dot < 0) mantissa else mantissa.substring(0, dot)) + fraction
    val significant = all.trimStart('0')
    if (significant.isEmpty()) return Decimal(negative, "0", 0)
    val digits = significant.trimEnd('0')
    return Decimal(
      negative,
      digits,
      fraction.length - exponent - (significant.length - digits.length),
    )
  }

  /** [d]'s magnitude rounded half up to [places] decimal places, as unscaled digits. */
  private fun rounded(d: Decimal, places: Int): String {
    val drop = d.scale - places
    if (drop <= 0) return d.digits + "0".repeat(-drop)
    val padded = "0".repeat((drop + 1 - d.digits.length).coerceAtLeast(0)) + d.digits
    val kept = padded.substring(0, padded.length - drop)
    return if (padded[padded.length - drop] >= '5') add(kept, "1") else kept
  }

  /** Unscaled [digits] with [places] decimal places, plainly: at least one integer digit. */
  private fun plain(digits: String, places: Int): String {
    val trimmed = digits.trimStart('0')
    val padded = trimmed.padStart(places + 1, '0')
    if (places == 0) return padded
    return padded.substring(0, padded.length - places) +
      "." +
      padded.substring(padded.length - places)
  }

  private fun add(a: String, b: String): String {
    val out = StringBuilder()
    var carry = 0
    var i = a.length - 1
    var j = b.length - 1
    while (i >= 0 || j >= 0 || carry > 0) {
      val sum = (if (i >= 0) a[i--] - '0' else 0) + (if (j >= 0) b[j--] - '0' else 0) + carry
      out.append('0' + sum % 10)
      carry = sum / 10
    }
    return out.reverse().toString().trimStart('0').ifEmpty { "0" }
  }

  /** [a] − [b], for [a] ≥ [b]. */
  private fun subtract(a: String, b: String): String {
    val out = StringBuilder()
    var borrow = 0
    var i = a.length - 1
    var j = b.length - 1
    while (i >= 0) {
      var diff = (a[i--] - '0') - (if (j >= 0) b[j--] - '0' else 0) - borrow
      borrow = if (diff < 0) 1 else 0
      if (diff < 0) diff += 10
      out.append('0' + diff)
    }
    return out.reverse().toString().trimStart('0').ifEmpty { "0" }
  }

  private fun compare(a: String, b: String): Int {
    val x = a.trimStart('0')
    val y = b.trimStart('0')
    return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
  }
}
