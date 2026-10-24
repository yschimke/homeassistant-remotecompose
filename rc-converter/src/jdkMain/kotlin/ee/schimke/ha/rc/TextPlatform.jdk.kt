package ee.schimke.ha.rc

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime

internal actual class PlatformLock actual constructor() {
  actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}

internal actual fun <T> runCaptureBlocking(block: suspend () -> T): T = runBlocking { block() }

internal actual fun formatFixed(value: Double, decimals: Int): String =
  "%.${decimals}f".format(Locale.ROOT, value)

internal actual fun decimalPlaces(value: Double): Int =
  BigDecimal.valueOf(value).stripTrailingZeros().scale().coerceAtLeast(0)

internal actual fun plainHalfUp(value: Double, digits: Int): String =
  BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

internal actual fun decimalSum(a: Double, b: Double): Double =
  BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)).toDouble()

internal actual fun formatDateTime(
  dateTime: LocalDateTime,
  pattern: String,
  english: Boolean,
): String {
  val formatter =
    if (english) DateTimeFormatter.ofPattern(pattern, Locale.US)
    else DateTimeFormatter.ofPattern(pattern)
  return formatter.format(dateTime.toJavaLocalDateTime())
}
