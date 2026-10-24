package ee.schimke.ha.rc

import kotlinx.datetime.LocalDateTime

internal actual fun formatFixed(value: Double, decimals: Int): String =
  DecimalStrings.formatFixed(value, decimals)

internal actual fun decimalPlaces(value: Double): Int = DecimalStrings.decimalPlaces(value)

internal actual fun plainHalfUp(value: Double, digits: Int): String =
  DecimalStrings.plainHalfUp(value, digits)

internal actual fun decimalSum(a: Double, b: Double): Double = DecimalStrings.sum(a, b)

internal actual fun formatDateTime(
  dateTime: LocalDateTime,
  pattern: String,
  english: Boolean,
): String = DateTimePatterns.format(dateTime, pattern)
